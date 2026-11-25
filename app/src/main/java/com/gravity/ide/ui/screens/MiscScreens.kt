@file:OptIn(ExperimentalMaterial3Api::class)

package com.gravity.ide.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.gravity.ide.ai.local.LocalLlamaProvider
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gravity.ide.BuildConfig
import com.gravity.ide.agent.AgentRole
import com.gravity.ide.ai.ReasoningMode
import com.gravity.ide.ai.local.LlamaBridge
import com.gravity.ide.data.db.MessageEntity
import com.gravity.ide.data.settings.AppSettings
import com.gravity.ide.data.settings.OperatingMode
import com.gravity.ide.data.settings.ThemeMode
import com.gravity.ide.security.SecretStore
import com.gravity.ide.ui.LocalAppContainer
import com.gravity.ide.ui.Routes
import com.gravity.ide.ui.components.EmptyState
import com.gravity.ide.ui.components.KeyValue
import com.gravity.ide.ui.components.LoadingRow
import com.gravity.ide.ui.components.MessageBanner
import com.gravity.ide.ui.components.Panel
import com.gravity.ide.ui.components.SectionHeader
import com.gravity.ide.ui.components.Selector
import com.gravity.ide.ui.components.StatusPill
import com.gravity.ide.ui.components.TextInputDialog
import com.gravity.ide.ui.components.formatBytes
import com.gravity.ide.ui.components.relativeTime
import com.gravity.ide.ui.drawerItems
import com.gravity.ide.ui.theme.GravityColors
import com.gravity.ide.workspace.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface Control {
    data class Toggle(val get: (AppSettings) -> Boolean, val set: (AppSettings, Boolean) -> AppSettings) : Control
    data class Choice(val options: List<Pair<String, String>>, val get: (AppSettings) -> String, val set: (AppSettings, String) -> AppSettings) : Control
    data class Num(val get: (AppSettings) -> Int, val set: (AppSettings, Int) -> AppSettings, val range: IntRange, val step: Int = 1) : Control
    data class Secret(val key: String) : Control
    data class Link(val route: String) : Control
    data class Action(val label: String, val run: (Context) -> Unit) : Control
    data class Info(val text: (AppSettings) -> String) : Control
    /** The default AI, shown by name (looked up from the providers list). */
    object DefaultModel : Control
}

/** Top-level groups, like a phone's Settings app: tap one to open its own page. */
private enum class SettingsPage(val title: String, val summary: String, val icon: ImageVector) {
    GENERAL("General", "Notifications, language", Icons.Filled.Notifications),
    DISPLAY("Display", "Theme (day / night), text size", Icons.Filled.Palette),
    VOICE("Voice & speech", "AI Butler + 12 more styles, male / female voices, accent, speed", Icons.Filled.RecordVoiceOver),
    AI("AI & models", "Cloud AI, default model, thinking, web search", Icons.Filled.SmartToy),
    PHONE_AI("Phone AI", "Offline models, speed, memory", Icons.Filled.PhoneAndroid),
    APPS("Agent & apps", "Approvals, steps, tasks at once", Icons.Filled.AutoAwesome),
    PRIVACY("Privacy & security", "Secrets, local-only projects", Icons.Filled.Lock),
    STORAGE("Storage & backup", "Space used, backups, cache", Icons.Filled.Backup),
    DEVELOPER("Developer tools", "Files, editor, terminal, builds, ADB, testing", Icons.Filled.Code),
    ABOUT("About & help", "Self-test, version", Icons.Filled.Info),
}

private data class SettingItem(val page: SettingsPage, val title: String, val description: String, val control: Control)

private fun numberLabel(n: Int) = "%,d".format(n)

private val settingItems: List<SettingItem> = listOf(
    // General
    SettingItem(SettingsPage.GENERAL, "Notifications", "When a task finishes and while models download", Control.Toggle({ it.notifications }, { s, v -> s.copy(notifications = v) })),
    SettingItem(SettingsPage.GENERAL, "Notification settings", "Android's settings for Gravity's notifications", Control.Action("Open") { ctx ->
        ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }),
    SettingItem(
        SettingsPage.GENERAL, "Keep running in the background",
        "So replies and app builds go on when you leave or swipe Gravity away. Some phones (Xiaomi, POCO, Redmi, Vivo, Oppo) close apps to save battery; Android asks you to allow Gravity",
        Control.Action("Allow") { ctx ->
            val pm = ctx.getSystemService(android.os.PowerManager::class.java)
            val intent = if (pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true) {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) // already allowed: show the list
            } else {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            }
            runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        },
    ),
    SettingItem(SettingsPage.GENERAL, "App language", "Android's language setting for Gravity", Control.Action("Open") { ctx ->
        if (Build.VERSION.SDK_INT >= 33) ctx.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else ctx.startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }),
    // Display
    SettingItem(SettingsPage.DISPLAY, "Theme", "Neon black, warm night, day, or the same as the phone", Control.Choice(
        listOf(ThemeMode.NEON.name to "Neon black", ThemeMode.DARK.name to "Night (warm dark)", ThemeMode.LIGHT.name to "Day (light)", ThemeMode.SYSTEM.name to "Same as phone (neon / day)"),
        { it.theme.name }, { s, v -> s.copy(theme = ThemeMode.valueOf(v)) },
    )),
    SettingItem(SettingsPage.DISPLAY, "Code editor text size", "Size of code in the editor", Control.Num({ it.editorFontSize }, { s, v -> s.copy(editorFontSize = v) }, 8..28)),
    SettingItem(SettingsPage.DISPLAY, "Terminal text size", "Size of text in the terminal", Control.Num({ it.terminalFontSize }, { s, v -> s.copy(terminalFontSize = v) }, 8..28)),
    // AI & models
    SettingItem(SettingsPage.AI, "Default AI", "Used for new chats and apps. Change it with ★ in AI providers, or pick a phone model", Control.DefaultModel),
    SettingItem(SettingsPage.AI, "AI providers & keys", "Gemini, OpenAI-compatible and custom AI; keys are stored encrypted", Control.Link(Routes.PROVIDERS)),
    SettingItem(SettingsPage.AI, "Operating mode", "Where the AI runs: cloud, phone or both", Control.Choice(OperatingMode.entries.map { it.name to it.label }, { it.operatingMode.name }, { s, v -> s.copy(operatingMode = OperatingMode.valueOf(v)) })),
    SettingItem(SettingsPage.AI, "Thinking", "How long the AI thinks before answering (longer is slower, often better)", Control.Choice(ReasoningMode.entries.map { it.name to it.label }, { it.reasoning.name }, { s, v -> s.copy(reasoning = ReasoningMode.valueOf(v)) })),
    SettingItem(SettingsPage.AI, "Use the phone model when offline", "Cloud tasks switch to the loaded phone model", Control.Toggle({ it.offlineFallbackToLocal }, { s, v -> s.copy(offlineFallbackToLocal = v) })),
    SettingItem(SettingsPage.AI, "Retry cloud tasks when back online", "Tasks waiting for the network continue by themselves", Control.Toggle({ it.retryWhenOnline }, { s, v -> s.copy(retryWhenOnline = v) })),
    SettingItem(SettingsPage.AI, "Web search", "Gemini chats can also search Google by themselves", Control.Choice(listOf("none" to "Off", "brave" to "Brave Search"), { it.webSearchProvider }, { s, v -> s.copy(webSearchProvider = v) })),
    SettingItem(SettingsPage.AI, "Brave Search API key", "Needed for Brave Search; stored encrypted", Control.Secret(SecretStore.BRAVE_KEY)),
    SettingItem(
        SettingsPage.AI, "Pictures and videos",
        "Online: real AI pictures from Pollinations.ai (free, no account; your picture description is sent there; 1 picture per 15 s, may carry a small watermark). Draw: the chat's AI draws it on this phone (works offline, simpler pictures)",
        Control.Choice(listOf("ask" to "Ask me the first time", "online" to "Free online image AI", "draw" to "Draw on this phone"), { it.pictureMaker }, { s, v -> s.copy(pictureMaker = v) }),
    ),
    SettingItem(SettingsPage.AI, "AI teams", "Architect, developer and reviewer working together", Control.Link(Routes.TEAMS)),
    SettingItem(SettingsPage.AI, "Offline readiness", "Check what works without the internet", Control.Link(Routes.OFFLINE)),
    // Phone AI
    SettingItem(SettingsPage.PHONE_AI, "Phone models", "Download, check and load offline models", Control.Link(Routes.models(0))),
    SettingItem(SettingsPage.PHONE_AI, "Hugging Face token", "Only for gated models; stored encrypted", Control.Secret(SecretStore.HF_TOKEN)),
    SettingItem(SettingsPage.PHONE_AI, "Memory for answers (context)", "More lets the phone model read and write bigger apps, but needs more RAM", Control.Choice(
        listOf(2048, 4096, 8192, 16384, 32768).map { n ->
            n.toString() to numberLabel(n) + " tokens" + when (n) { 2048 -> " (low RAM)"; 4096 -> " (normal)"; 16384 -> " (big apps)"; 32768 -> " (most RAM)"; else -> "" }
        },
        { it.localContextSize.toString() }, { s, v -> s.copy(localContextSize = v.toInt()) },
    )),
    SettingItem(SettingsPage.PHONE_AI, "Speed", "How many CPU cores the phone model uses (the phone's temperature still limits it)", Control.Choice(listOf("ADAPTIVE" to "Adaptive", "HIGH" to "Fastest", "LOW" to "Battery saver"), { it.performanceMode }, { s, v -> s.copy(performanceMode = v) })),
    SettingItem(SettingsPage.PHONE_AI, "CPU threads", "Used by Adaptive speed", Control.Num({ it.localThreads }, { s, v -> s.copy(localThreads = v) }, 1..16)),
    SettingItem(SettingsPage.PHONE_AI, "Low memory mode", "2k context, 2 threads, one task at a time", Control.Toggle({ it.lowMemoryMode }, { s, v -> s.copy(lowMemoryMode = v) })),
    // Agent & apps
    SettingItem(SettingsPage.APPS, "Ask before every command", "Otherwise only risky commands need your OK", Control.Toggle({ it.approveAllCommands }, { s, v -> s.copy(approveAllCommands = v) })),
    SettingItem(SettingsPage.APPS, "Ask before every file change", "Otherwise only sensitive files need your OK", Control.Toggle({ it.approveFileWrites }, { s, v -> s.copy(approveFileWrites = v) })),
    SettingItem(SettingsPage.APPS, "Steps per run", "A task pauses after this many steps (Resume continues)", Control.Num({ it.agentMaxSteps }, { s, v -> s.copy(agentMaxSteps = v) }, 1..200, step = 5)),
    SettingItem(SettingsPage.APPS, "Tasks at once", "Independent tasks that may run together", Control.Num({ it.maxParallelTasks }, { s, v -> s.copy(maxParallelTasks = v) }, 1..8)),
    // Privacy & security
    SettingItem(SettingsPage.PRIVACY, "Hide secrets from cloud AI", "API keys, tokens and private keys are replaced before anything is sent", Control.Toggle({ it.redactSecrets }, { s, v -> s.copy(redactSecrets = v) })),
    SettingItem(SettingsPage.PRIVACY, "Local-only projects", "In a project's settings: its files never go to cloud AI", Control.Link(Routes.PROJECTS_LIST)),
    // Storage & backup (space used is shown above these)
    SettingItem(SettingsPage.STORAGE, "Backup & recovery", "Encrypted backups, trash and old versions", Control.Link(Routes.BACKUP)),
    SettingItem(SettingsPage.STORAGE, "Where projects are kept", "Private app storage: kept across restarts, removed on uninstall (back up!)", Control.Info { "/data/data/${BuildConfig.APPLICATION_ID}/files/workspaces" }),
    // Developer tools
    SettingItem(SettingsPage.DEVELOPER, "Show hidden files", "Files and folders that start with a dot", Control.Toggle({ it.showHiddenFiles }, { s, v -> s.copy(showHiddenFiles = v) })),
    SettingItem(SettingsPage.DEVELOPER, "Auto-save", "Save 2.5 s after the last edit (a version is kept each time)", Control.Toggle({ it.autoSave }, { s, v -> s.copy(autoSave = v) })),
    SettingItem(SettingsPage.DEVELOPER, "Build Center", "Build backends and what this phone can build", Control.Link(Routes.build())),
    SettingItem(SettingsPage.DEVELOPER, "Toolchains (JDK, SDK, Gradle, Node)", "Android does not let apps run them from their own storage; install them in Termux", Control.Link(Routes.build())),
    SettingItem(SettingsPage.DEVELOPER, "Testing Center", "Screen capture, app tests, self-test results", Control.Link(Routes.TESTING)),
    SettingItem(SettingsPage.DEVELOPER, "Android developer options", "USB / wireless debugging (for Shizuku or a computer)", Control.Action("Open") { ctx -> ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }),
    SettingItem(SettingsPage.DEVELOPER, "Plugins", "Extensions and their status", Control.Link(Routes.PLUGINS)),
    SettingItem(SettingsPage.DEVELOPER, "Experimental features", "None in this build", Control.Info { "" }),
    // About & help
    SettingItem(SettingsPage.ABOUT, "Self-test & diagnostics", "Run real checks on this phone", Control.Link(Routes.HELP)),
    SettingItem(SettingsPage.ABOUT, "About Gravity", "Version and build information", Control.Link(Routes.ABOUT)),
)

@Composable
fun SettingsScreen(go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by c.settings.settings.collectAsState(initial = AppSettings())
    var query by rememberSaveable { mutableStateOf("") }
    var pageName by rememberSaveable { mutableStateOf<String?>(null) }
    val page = pageName?.let { n -> SettingsPage.entries.firstOrNull { it.name == n } }
    var secretEdit by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf<SettingItem?>(null) }
    var secretsVersion by remember { mutableStateOf(0) }
    BackHandler(enabled = page != null) { pageName = null }

    val defaultModelText by produceState("…", settings.defaultProviderId, settings.defaultModel) {
        val id = settings.defaultProviderId
        value = when {
            id == null -> "Not set: Gravity picks the best AI available"
            id == LocalLlamaProvider.ID -> "Phone model" + (settings.defaultModel?.let { " · $it" } ?: "")
            else -> (withContext(Dispatchers.IO) { c.db.providers().get(id)?.name } ?: "Removed provider") + " · " + (settings.defaultModel ?: "its default model")
        }
    }

    fun update(t: (AppSettings) -> AppSettings) {
        scope.launch {
            val before = c.settings.current().operatingMode
            c.settings.update(t)
            // Spec §17: inspect readiness when the user switches to offline (Local only).
            if (before != OperatingMode.LOCAL_ONLY && c.settings.current().operatingMode == OperatingMode.LOCAL_ONLY) go(Routes.OFFLINE)
        }
    }

    @Composable
    fun ItemRow(item: SettingItem) {
        // secretsVersion is read so a saved key shows "Saved" at once.
        val saved = secretsVersion >= 0 && (item.control as? Control.Secret)?.let { c.secrets.has(it.key) } == true
        SettingRow(
            item, settings, ::update, go, context,
            secretSaved = saved,
            defaultModel = defaultModelText,
            onSecret = { secretEdit = it },
            onChoose = { choosing = it },
        )
    }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (page == null) {
            item {
                OutlinedTextField(
                    query, { query = it }, placeholder = { Text("Search settings") }, singleLine = true,
                    shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().testTag("settings-search"),
                )
            }
            if (query.isBlank()) {
                item {
                    SettingsCard {
                        SettingsPage.entries.forEachIndexed { i, p ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            PageRow(p) { pageName = p.name }
                        }
                    }
                }
            } else {
                val hits = settingItems.filter {
                    it.title.contains(query, true) || it.description.contains(query, true) || it.page.title.contains(query, true)
                }
                if (hits.isEmpty()) item { EmptyState("Nothing found", "Try another word, for example theme, model or backup.") }
                else item {
                    SettingsCard {
                        hits.forEachIndexed { i, si ->
                            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            Text(si.page.title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
                            ItemRow(si)
                        }
                    }
                }
            }
        } else {
            item {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { pageName = null }, modifier = Modifier.testTag("settings-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to all settings")
                    }
                    Icon(page.icon, null, tint = GravityColors.Accent, modifier = Modifier.padding(end = 10.dp).size(22.dp))
                    Text(page.title, style = MaterialTheme.typography.titleLarge)
                }
            }
            if (page == SettingsPage.STORAGE) item { StorageCard() }
            if (page == SettingsPage.VOICE) item { VoiceSettingsPanel() }
            if (settingItems.any { it.page == page }) item {
                SettingsCard {
                    settingItems.filter { it.page == page }.forEachIndexed { i, si ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        ItemRow(si)
                    }
                }
            }
        }
    }

    val chosen = choosing
    val chosenCtl = chosen?.control as? Control.Choice
    if (chosen != null && chosenCtl != null) {
        val current = chosenCtl.get(settings)
        AlertDialog(
            onDismissRequest = { choosing = null },
            title = { Text(chosen.title) },
            text = {
                Column {
                    chosenCtl.options.forEach { (value, label) ->
                        androidx.compose.foundation.layout.Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .clickable { update { st -> chosenCtl.set(st, value) }; choosing = null }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == current, onClick = { update { st -> chosenCtl.set(st, value) }; choosing = null })
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = null }) { Text("Close") } },
        )
    }
    secretEdit?.let { key ->
        TextInputDialog(
            "Set key", "Paste the key (stored encrypted)", "", "Save",
            onConfirm = { v -> c.secrets.put(key, v); secretsVersion++; toast(context, "Saved (encrypted)") },
            onDismiss = { secretEdit = null },
        )
    }
}

/** A rounded group of rows, like the cards in a phone's Settings app. */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun PageRow(page: SettingsPage, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp).testTag("settings-page-${page.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(GravityColors.Accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(page.icon, null, tint = GravityColors.Accent, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(page.title, style = MaterialTheme.typography.titleMedium)
            Text(page.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingRow(
    item: SettingItem,
    s: AppSettings,
    update: ((AppSettings) -> AppSettings) -> Unit,
    go: (String) -> Unit,
    context: Context,
    secretSaved: Boolean,
    defaultModel: String,
    onSecret: (String) -> Unit,
    onChoose: (SettingItem) -> Unit,
) {
    val ctl = item.control
    val onClick: (() -> Unit)? = when (ctl) {
        is Control.Toggle -> { { update { ctl.set(it, !ctl.get(s)) } } }
        is Control.Choice -> { { onChoose(item) } }
        is Control.Secret -> { { onSecret(ctl.key) } }
        is Control.Link -> { { go(ctl.route) } }
        is Control.Action -> { { runCatching { ctl.run(context) }.onFailure { toast(context, "Not available on this phone") } } }
        Control.DefaultModel -> { { go(Routes.PROVIDERS) } }
        is Control.Num, is Control.Info -> null
    }
    val value: String? = when (ctl) {
        is Control.Choice -> ctl.get(s).let { v -> ctl.options.firstOrNull { it.first == v }?.second ?: v }
        is Control.Secret -> if (secretSaved) "Saved" else "Not set"
        is Control.Info -> ctl.text(s).ifBlank { null }
        Control.DefaultModel -> defaultModel
        else -> null
    }
    val base = Modifier.fillMaxWidth()
    androidx.compose.foundation.layout.Row(
        (if (onClick != null) base.clickable(onClick = onClick) else base).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)
            if (item.description.isNotBlank()) {
                Text(item.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (value != null) {
                Text(
                    value, style = MaterialTheme.typography.bodyMedium,
                    color = if (ctl is Control.Info) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        when (ctl) {
            is Control.Toggle -> Switch(ctl.get(s), { v -> update { ctl.set(it, v) } })
            is Control.Num -> {
                val n = ctl.get(s)
                IconButton(onClick = { update { ctl.set(it, (n - ctl.step).coerceIn(ctl.range)) } }, enabled = n > ctl.range.first) {
                    Icon(Icons.Filled.Remove, "Less")
                }
                Text(n.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
                IconButton(onClick = { update { ctl.set(it, (n + ctl.step).coerceIn(ctl.range)) } }, enabled = n < ctl.range.last) {
                    Icon(Icons.Filled.Add, "More")
                }
            }
            is Control.Link, Control.DefaultModel, is Control.Choice, is Control.Secret ->
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            is Control.Action -> Icon(Icons.Filled.Launch, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            is Control.Info -> Unit
        }
    }
}

/** Space used by Gravity, with Clear cache. */
@Composable
private fun StorageCard() {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var storage by remember { mutableStateOf<List<Pair<String, Long>>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        storage = withContext(Dispatchers.IO) {
            val files = c.appContext.filesDir
            listOf("Projects" to "workspaces", "Phone models" to "models", "Versions" to "checkpoints", "Screenshots" to "screenshots", "Trash" to "trash", "Builds" to "builds")
                .map { (label, dir) -> label to File(files, dir).walkBottomUp().filter { f -> f.isFile }.sumOf { f -> f.length() } } +
                ("Cache" to c.appContext.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() })
        }
    }
    SettingsCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Space used", style = MaterialTheme.typography.titleMedium)
            val s = storage
            if (s == null) LoadingRow("Measuring…") else {
                s.forEach { (name, size) -> KeyValue(name, formatBytes(size)) }
                KeyValue("Free on this phone", formatBytes(c.models.freeBytes()))
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { c.appContext.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
                        refresh++
                        toast(context, "Cache cleared")
                    }
                }) { Text("Clear cache") }
            }
        }
    }
}

@Composable
fun GitScreen(go: (String) -> Unit) {
    val c = LocalAppContainer.current
    var gitPath by remember { mutableStateOf<String?>(null) }
    val versions by c.db.fileVersions().observeRecent().collectAsState(initial = emptyList())
    LaunchedEffect(Unit) {
        gitPath = withContext(Dispatchers.IO) {
            try {
                val p = ProcessBuilder("/system/bin/sh", "-c", "command -v git || true").redirectErrorStream(true).start()
                p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
            } catch (e: Exception) {
                ""
            }
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            MessageBanner(
                "Git integration is not implemented in this build. Android ships no git binary and Gravity does not yet bundle a Git library (JGit integration is planned — see docs/KNOWN_LIMITATIONS.md). " +
                    "Until then, Gravity's checkpoints record a version of every file before each save and agent edit, with diff, per-task rollback and restore.",
                isError = true,
            )
        }
        item {
            Panel {
                Column {
                    KeyValue("git on PATH", gitPath?.ifBlank { "not found" } ?: "checking…", mono = true)
                    KeyValue("Checkpoints", "${versions.size} recent versions")
                    if (!gitPath.isNullOrBlank()) Text("A git binary was found: you can run git commands from the Terminal.", style = MaterialTheme.typography.bodySmall, color = GravityColors.Green)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { go(Routes.BACKUP) }) { Text("Version history") }
                        TextButton(onClick = { go(Routes.terminal()) }) { Text("Terminal") }
                    }
                }
            }
        }
    }
}

@Composable
fun PluginsScreen() {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            MessageBanner(
                "No third-party plugin runtime is included in this build, so nothing can be installed here yet. Planned design: plugins as separate APKs exposing tools over a bound service, " +
                    "with declared permissions the user approves and no access to credentials or other projects.",
                isError = true,
            )
        }
        item { SectionHeader("Built-in agent tools") }
        val tools = listOf(
            "list_directory, read_file, search_code" to "Read-only, project root only",
            "write_file, replace_in_file, create_directory, move_path" to "Project root only; checkpointed; approval for sensitive files",
            "delete_path" to "Always requires approval; moves to trash",
            "run_command" to "Device shell; approval for risky commands",
            "build_project" to "Runs the project's build backend (Gravity shell, Termux, static web); real exit code",
            "install_apk" to "Requires approval, then the system install dialog",
            "test_app" to "Launch + logcat crash check + screenshot; real PASS/FAIL",
            "web_search, fetch_url" to "Network; results treated as untrusted data",
            "finish" to "Ends the task with a summary",
        )
        items(tools) { (name, perm) ->
            Panel {
                Column {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text(perm, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { SectionHeader("Agent roles") }
        items(AgentRole.entries.toList()) { r -> Text("• ${r.label}: ${r.brief}", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun HelpScreen(go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val results by c.db.testResults().observe().collectAsState(initial = emptyList())
    var running by remember { mutableStateOf(false) }
    val selfTests = results.filter { it.suite == "self-test" }.distinctBy { it.name }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionHeader("Self-test")
            Text("Runs real checks on this device (database, Keystore, files, trash, checkpoints, shell exit codes, ZIP safety, GGUF parsing, backup crypto, network, providers, local inference) and records evidence.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = !running, onClick = {
                running = true
                scope.launch {
                    c.selfTest.runAll()
                    running = false
                }
            }, modifier = Modifier.padding(top = 6.dp)) { Text("Run self-test") }
            if (running) LoadingRow("Running…")
        }
        items(selfTests, key = { it.id }) { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.bodySmall)
                    Text(r.actual, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                StatusPill(r.outcome)
            }
        }
        item {
            SectionHeader("Diagnostics")
            Panel {
                Column {
                    KeyValue("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
                    KeyValue("Local runtime", if (LlamaBridge.isAvailable) "llama.cpp bundled" else (LlamaBridge.loadError ?: "unavailable"))
                    KeyValue("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.joinToString()}")
                    KeyValue("Data dir", c.appContext.filesDir.absolutePath, mono = true)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { go(Routes.OFFLINE) }) { Text("Offline readiness") }
                TextButton(onClick = { go(Routes.build()) }) { Text("Feasibility report") }
                TextButton(onClick = { go(Routes.ACTIVITY) }) { Text("Activity logs") }
                TextButton(onClick = { go(Routes.EXPORT) }) { Text("Export logs") }
            }
        }
        item {
            SectionHeader("Getting started")
            Text(
                "1. AI Providers → add Gemini (API key from Google AI Studio) or an OpenAI-compatible endpoint, then Test connection.\n" +
                    "2. Projects → New → pick a template.\n3. AI Agent → describe the change → approve risky steps → review Changes → roll back if needed.\n" +
                    "4. Model Manager → download a small GGUF model for offline work (needs an APK with the llama.cpp runtime).\n" +
                    "5. Testing Center → start capture → install/launch your app → run a test → AI-analyse the screenshot.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun AboutScreen() {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("GRAVITY", style = MaterialTheme.typography.headlineMedium, color = GravityColors.Cyan)
            Text("Hybrid autonomous AI development environment for Android", style = MaterialTheme.typography.bodyMedium)
            Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SectionHeader("Principles")
            Text(
                "No simulated output: terminal output, builds, tests, downloads, screenshots and AI responses are real or reported as unavailable. " +
                    "Platform limits are shown where they apply (Build Center feasibility, Git, Plugins, shell notes).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            SectionHeader("Open-source components")
            Text("Jetpack Compose, Room, DataStore, WorkManager, AndroidX WebKit & DocumentFile (Apache-2.0) · OkHttp (Apache-2.0) · kotlinx.coroutines (Apache-2.0) · llama.cpp (MIT, optional build).", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun GlobalSearchScreen(go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val projects by c.db.projects().observeActive().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var fileHits by remember { mutableStateOf<List<Pair<String, SearchHit>>>(emptyList()) }
    var messageHits by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.length < 2) {
            fileHits = emptyList(); messageHits = emptyList(); return@LaunchedEffect
        }
        kotlinx.coroutines.delay(300)
        searching = true
        fileHits = withContext(Dispatchers.IO) { projects.flatMap { p -> c.workspace.search(File(p.rootPath), query, maxResults = 40).map { p.id to it } }.take(200) }
        messageHits = c.db.chats().searchMessages(query)
        searching = false
    }
    val navHits = drawerItems.filter { query.length >= 2 && it.label.contains(query, true) }
    val projectHits = projects.filter { query.length >= 2 && it.name.contains(query, true) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { OutlinedTextField(query, { query = it }, placeholder = { Text("Search files, chats, projects, screens") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        if (searching) item { LoadingRow("Searching local data…") }
        if (navHits.isNotEmpty()) item { SectionHeader("Screens") }
        items(navHits, key = { "n" + it.route }) { n -> Text(n.label, modifier = Modifier.fillMaxWidth().clickable { go(n.route) }.padding(vertical = 8.dp)) }
        if (projectHits.isNotEmpty()) item { SectionHeader("Projects") }
        items(projectHits, key = { "p" + it.id }) { p -> Text(p.name, modifier = Modifier.fillMaxWidth().clickable { go(Routes.files(p.id)) }.padding(vertical = 8.dp)) }
        if (fileHits.isNotEmpty()) item { SectionHeader("Files (${fileHits.size})") }
        items(fileHits) { (pid, h) ->
            Column(Modifier.fillMaxWidth().clickable { go(Routes.editor(pid, h.relPath)) }.padding(vertical = 6.dp)) {
                Text("${projects.firstOrNull { it.id == pid }?.name}/${h.relPath}:${h.line}", style = MaterialTheme.typography.labelMedium, color = GravityColors.Cyan)
                Text(h.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (messageHits.isNotEmpty()) item { SectionHeader("Chat messages") }
        items(messageHits, key = { "m" + it.id }) { m ->
            Column(Modifier.fillMaxWidth().clickable { go(Routes.chat(m.chatId)) }.padding(vertical = 6.dp)) {
                Text(m.content.take(160), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(relativeTime(m.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (query.length >= 2 && !searching && navHits.isEmpty() && projectHits.isEmpty() && fileHits.isEmpty() && messageHits.isEmpty()) item { EmptyState("No results", "Web search is available to agents and Gemini chats when online.") }
    }
}

@Composable
fun WorkspacesScreen(go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val projects by c.db.projects().observeAll().collectAsState(initial = emptyList())
    var sizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(projects.size) { sizes = withContext(Dispatchers.IO) { projects.associate { it.id to c.workspace.storageUsage(File(it.rootPath)) } } }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Panel {
                Column {
                    KeyValue("Location", c.workspace.workspacesRoot.absolutePath, mono = true)
                    KeyValue("Projects", "${projects.size} (${projects.count { it.archived }} archived)")
                    KeyValue("Total size", formatBytes(sizes.values.sum()))
                    Text(
                        "Workspaces are restored automatically after restarts. Folders opened through Android's picker are copied in (so the terminal and agent get real paths) and can be copied back from the Export Center.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { go(Routes.PROJECTS_NEW) }) { Text("New project") }
                        TextButton(onClick = { go(Routes.PROJECTS_LIST) }) { Text("Open folder / import ZIP") }
                    }
                }
            }
        }
        if (projects.isEmpty()) item { EmptyState("No workspaces", "") }
        items(projects, key = { it.id }) { p ->
            Panel(onClick = { go(Routes.files(p.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name + if (p.archived) " (archived)" else "")
                        Text(File(p.rootPath).name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(formatBytes(sizes[p.id] ?: 0), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
