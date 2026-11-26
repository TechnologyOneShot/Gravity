@file:OptIn(ExperimentalMaterial3Api::class)

package com.gravity.ide.ui.screens

import com.gravity.ide.ui.components.safeTabIndicator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.sp
import com.gravity.ide.capture.CaptureState
import com.gravity.ide.capture.ScreenCaptureController
import com.gravity.ide.capture.ScreenCaptureService
import com.gravity.ide.device.AppInstaller
import com.gravity.ide.device.ApkInfo
import com.gravity.ide.device.InstalledApp
import com.gravity.ide.device.LogLine
import com.gravity.ide.ui.LocalAppContainer
import com.gravity.ide.ui.Routes
import com.gravity.ide.ui.components.EmptyState
import com.gravity.ide.ui.components.KeyValue
import com.gravity.ide.ui.components.LoadingRow
import com.gravity.ide.ui.components.MenuAction
import com.gravity.ide.ui.components.MessageBanner
import com.gravity.ide.ui.components.MonoBlock
import com.gravity.ide.ui.components.OverflowMenu
import com.gravity.ide.ui.components.Panel
import com.gravity.ide.ui.components.SectionHeader
import com.gravity.ide.ui.components.Selector
import com.gravity.ide.ui.components.StatusPill
import com.gravity.ide.ui.components.formatBytes
import com.gravity.ide.ui.components.relativeTime
import com.gravity.ide.ui.theme.GravityColors
import com.gravity.ide.ui.theme.MonoFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DevicesScreen(apkPath: String?, go: (String) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    Column {
        TabRow(selectedTabIndex = tab, indicator = safeTabIndicator(tab), containerColor = MaterialTheme.colorScheme.background) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Device") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Apps") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Logcat") })
        }
        when (tab) {
            0 -> DeviceTab(apkPath, go)
            1 -> AppsTab()
            else -> LogcatTab()
        }
    }
}

@Composable
private fun DeviceTab(apkPath: String?, go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val info = remember { c.device.info() }
    var apk by remember { mutableStateOf(apkPath?.let { File(it) }) }
    var apkInfo by remember { mutableStateOf<ApkInfo?>(null) }
    var installing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val dest = File(context.cacheDir, "picked-${System.currentTimeMillis()}.apk")
            withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { i -> dest.outputStream().use { i.copyTo(it) } } }
            apk = dest
        }
    }
    LaunchedEffect(apk) { apkInfo = apk?.let { f -> withContext(Dispatchers.IO) { c.installer.inspect(f) } } }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionHeader("This device")
            Panel {
                Column {
                    KeyValue("Model", "${info.manufacturer} ${info.model}")
                    KeyValue("Android", "${info.androidVersion} (API ${info.sdkInt})")
                    KeyValue("ABIs", info.abis.joinToString())
                    KeyValue("CPU cores", info.cpuCores.toString())
                    KeyValue("RAM", "${formatBytes(info.availRamBytes)} free / ${formatBytes(info.totalRamBytes)}${if (info.lowRam) " · low-RAM device" else ""}")
                    KeyValue("Storage", "${formatBytes(info.internalFreeBytes)} free / ${formatBytes(info.internalTotalBytes)}")
                    KeyValue("Battery", if (info.batteryPercent >= 0) "${info.batteryPercent}%${if (info.charging) " · charging" else ""}" else "unknown")
                    KeyValue("Thermal", info.thermalStatus)
                    KeyValue("OpenGL ES", info.glEsVersion)
                    KeyValue("Connection", "Same device (Yamini runs on it) — no ADB required for install/launch")
                }
            }
        }
        item {
            SectionHeader("Install an APK")
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (apk == null) Text("Pick an APK (for example one built by CI, Termux or Android Studio) or open one from Files / Build Center.", style = MaterialTheme.typography.bodySmall)
                    apk?.let { f ->
                        KeyValue("File", "${f.name} (${formatBytes(f.length())})")
                        val i = apkInfo
                        if (i == null) Text("Not a valid APK, or still inspecting…", color = GravityColors.Amber, style = MaterialTheme.typography.bodySmall) else {
                            KeyValue("Package", i.packageName, mono = true)
                            KeyValue("Version", "${i.versionName ?: "?"} (${i.versionCode})")
                            i.label?.let { KeyValue("Label", it) }
                            i.minSdk?.let { KeyValue("minSdk", "$it${if (it > Build.VERSION.SDK_INT) " — higher than this device!" else ""}") }
                        }
                    }
                    if (installing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }) { Text("Choose APK") }
                        Button(enabled = apk != null && apkInfo != null && !installing, onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                                toast(context, "Allow Yamini to install apps, then tap Install again")
                                return@Button
                            }
                            installing = true
                            scope.launch {
                                val outcome = try { c.installer.install(apk!!) } catch (e: Exception) { AppInstaller.InstallOutcome.Failure(-1, e.message ?: "error") }
                                installing = false
                                result = when (outcome) {
                                    is AppInstaller.InstallOutcome.Success -> "Installed ${outcome.packageName ?: ""}"
                                    is AppInstaller.InstallOutcome.Failure -> "Install failed: ${outcome.message}"
                                }
                            }
                        }) { Icon(Icons.Filled.InstallMobile, null); Text(" Install") }
                    }
                    result?.let { r ->
                        Text(r, color = if (r.startsWith("Installed")) GravityColors.Green else GravityColors.Red, style = MaterialTheme.typography.bodySmall)
                        val pkg = apkInfo?.packageName
                        if (r.startsWith("Installed") && pkg != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { if (!c.installer.launch(pkg)) toast(context, "No launcher activity") }) { Icon(Icons.Filled.Launch, null); Text(" Launch") }
                                OutlinedButton(onClick = { go(Routes.TESTING) }) { Text("Test it") }
                            }
                        }
                    }
                }
            }
        }
        item {
            MessageBanner(
                "Force-stop, instrumentation tests (am instrument) and input injection need adb-level privileges that Android does not give to apps. " +
                    "Yamini detects Shizuku/Wireless Debugging (Build Center → feasibility) but this build does not bridge to them yet; uninstall/launch/app-info use the system UI.",
            )
        }
    }
}

@Composable
private fun AppsTab() {
    val c = LocalAppContainer.current
    var includeSystem by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    LaunchedEffect(includeSystem) { apps = withContext(Dispatchers.IO) { c.device.installedApps(includeSystem) } }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Filter apps") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Include system apps", Modifier.weight(1f)); Switch(includeSystem, { includeSystem = it }) }
        }
        val list = apps
        if (list == null) item { LoadingRow("Reading installed packages…") }
        else items(list.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }, key = { it.packageName }) { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.label, maxLines = 1)
                    Text("${a.packageName} · ${a.versionName ?: ""}${if (a.debuggable) " · debuggable" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OverflowMenu(
                    listOf(
                        MenuAction("Launch", Icons.Filled.Launch) { c.installer.launch(a.packageName) },
                        MenuAction("App info", Icons.Filled.Info) { c.installer.openAppInfo(a.packageName) },
                        MenuAction("Uninstall", Icons.Filled.Delete, destructive = true) { c.installer.uninstall(a.packageName) },
                    )
                )
            }
        }
    }
}

@Composable
private fun LogcatTab() {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf('V') }
    val readLogs = remember { LocalContextHolder.granted(c.appContext) }
    fun load() {
        loading = true
        scope.launch {
            lines = try { c.logcat.dump(1500, filter = filter.ifBlank { null }) } catch (e: Exception) { listOf(LogLine("logcat failed: ${e.message}", 'E', "", null)) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }
    val order = "VDIWEF"
    val shown = lines.filter { order.indexOf(it.level).coerceAtLeast(0) >= order.indexOf(level) }
    val crashes = remember(lines) { c.logcat.findCrashes(lines) }
    Column {
        if (!readLogs) MessageBanner("Showing Yamini's own log only. To read other apps' logs, grant once from a computer: adb shell pm grant ${c.appContext.packageName} android.permission.READ_LOGS")
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(filter, { filter = it }, placeholder = { Text("Filter (tag, package, text)") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { load() }) { Text("Refresh") }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (l in "VDIWE") FilterChip(selected = level == l, onClick = { level = l }, label = { Text(l.toString()) })
            TextButton(onClick = { scope.launch { c.logcat.clear(); load() } }) { Text("Clear") }
        }
        if (crashes.isNotEmpty()) {
            Text("${crashes.size} crash(es) found: ${crashes.first().packageName ?: "?"} — ${crashes.first().exception}", color = GravityColors.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(contentPadding = PaddingValues(8.dp)) {
            items(shown) { l ->
                Text(
                    l.raw, fontFamily = MonoFamily, fontSize = 10.sp, lineHeight = 13.sp,
                    color = when (l.level) { 'E', 'F' -> GravityColors.Red; 'W' -> GravityColors.Amber; 'I' -> MaterialTheme.colorScheme.onSurface; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                )
            }
        }
    }
}

private object LocalContextHolder {
    fun granted(context: Context) = context.checkSelfPermission(android.Manifest.permission.READ_LOGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

@Composable
fun TestingScreen(go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val capture by ScreenCaptureController.state.collectAsState()
    val results by c.db.testResults().observe().collectAsState(initial = emptyList())
    val providers by c.db.providers().observe().collectAsState(initial = emptyList())
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var target by remember { mutableStateOf<InstalledApp?>(null) }
    var wait by remember { mutableStateOf("5") }
    var running by remember { mutableStateOf(false) }
    var analysis by remember { mutableStateOf<String?>(null) }
    var lastShotId by remember { mutableStateOf<Long?>(null) }
    var suite by remember { mutableStateOf("all") }
    var selfTesting by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { c.device.installedApps(false) } }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) ScreenCaptureService.start(context, r.resultCode, r.data!!)
        else toast(context, "Screen capture was not allowed")
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionHeader("Screen capture")
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (val s = capture) {
                        is CaptureState.Active -> Text("Capture session active (${s.width}×${s.height})", color = GravityColors.Green)
                        is CaptureState.Error -> Text(s.message, color = GravityColors.Red)
                        CaptureState.Idle -> Text("Not capturing. Android asks for consent once per session.", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (capture is CaptureState.Active) {
                            OutlinedButton(onClick = { ScreenCaptureService.stop(context) }) { Text("Stop capture") }
                            Button(onClick = {
                                scope.launch {
                                    try {
                                        val s = c.screenshots.captureNow("Manual capture", null)
                                        lastShotId = s.id
                                        toast(context, "Saved ${s.width}×${s.height} screenshot")
                                    } catch (e: Exception) {
                                        toast(context, e.message ?: "Capture failed")
                                    }
                                }
                            }) { Icon(Icons.Filled.Screenshot, null); Text(" Capture now") }
                        } else {
                            Button(onClick = {
                                val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                consent.launch(mpm.createScreenCaptureIntent())
                            }) { Text("Start capture session") }
                        }
                    }
                }
            }
        }
        item {
            SectionHeader("Run an app test")
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Selector("Target app", apps, target, { "${it.label} (${it.packageName})" }, { target = it }, Modifier.fillMaxWidth())
                    OutlinedTextField(wait, { wait = it.filter(Char::isDigit) }, label = { Text("Run for (seconds) before capture + log check") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Launches the app, waits, captures a real screenshot (if a capture session is active), reads logcat and records PASS only when no FATAL EXCEPTION for the package appears.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Button(enabled = target != null && !running, onClick = {
                        val t = target ?: return@Button
                        running = true
                        scope.launch {
                            try {
                                val r = c.appTests.run(t.packageName, wait.toIntOrNull() ?: 5, capture = true)
                                lastShotId = r.screenshot?.id
                                toast(context, "${r.result.outcome}: ${r.result.actual}")
                            } catch (e: Exception) {
                                toast(context, "Test error: ${e.message}")
                            } finally {
                                running = false
                            }
                        }
                    }) { Text("Run test") }
                    lastShotId?.let { id ->
                        TextButton(onClick = {
                            scope.launch {
                                val shot = c.db.screenshots().get(id) ?: return@launch
                                analysis = "Analysing…"
                                analysis = try { c.appTests.analyze(shot, providers.firstOrNull { it.enabled && it.kind != "LOCAL" }?.id, "") } catch (e: Exception) { "Analysis failed: ${e.message}" }
                            }
                        }) { Text("AI visual analysis of the last screenshot") }
                    }
                    analysis?.let { RichText(it) }
                }
            }
        }
        item {
            SectionHeader("Results") {
                TextButton(enabled = !selfTesting, onClick = {
                    selfTesting = true
                    scope.launch {
                        val r = c.selfTest.runAll()
                        selfTesting = false
                        toast(context, "Self-test: ${r.count { it.outcome == "PASS" }}/${r.size} passed")
                    }
                }) { Text("Run self-test") }
            }
            if (selfTesting) LoadingRow("Running real checks on this device…")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in listOf("all", "self-test", "feasibility", "device")) FilterChip(selected = suite == s, onClick = { suite = s }, label = { Text(s) })
            }
        }
        val shown = results.filter { suite == "all" || it.suite == suite || (suite == "device" && it.suite.startsWith("device:")) }
        if (shown.isEmpty()) item { EmptyState("No results yet", "Run the self-test, the feasibility probe (Build Center) or an app test.") }
        items(shown, key = { it.id }) { r ->
            Panel(onClick = { expanded = if (expanded == r.id) null else r.id }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        StatusPill(r.outcome)
                    }
                    Text("${r.suite} · ${relativeTime(r.createdAt)} · ${r.durationMs} ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (expanded == r.id) {
                        KeyValue("Expected", r.expected)
                        KeyValue("Actual", r.actual)
                        r.evidencePath?.let { KeyValue("Evidence", it, mono = true) }
                        if (r.details.isNotBlank()) MonoBlock(r.details.take(6000), Modifier.heightIn(max = 300.dp))
                    }
                }
            }
        }
        item { TextButton(onClick = { go(Routes.SCREENSHOTS) }) { Text("Open Screenshot Gallery") } }
    }
}
