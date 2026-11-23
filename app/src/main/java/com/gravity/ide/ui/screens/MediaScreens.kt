@file:OptIn(ExperimentalMaterial3Api::class)

package com.gravity.ide.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import com.gravity.ide.capture.ImageDiff
import com.gravity.ide.data.db.ScreenshotEntity
import com.gravity.ide.ui.LocalAppContainer
import com.gravity.ide.ui.components.EmptyState
import com.gravity.ide.ui.components.KeyValue
import com.gravity.ide.ui.components.formatTimeFull
import com.gravity.ide.ui.theme.GravityColors
import com.gravity.ide.ui.theme.MonoFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ScreenshotsScreen() {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val shots by c.db.screenshots().observe().collectAsState(initial = emptyList())
    val providers by c.db.providers().observe().collectAsState(initial = emptyList())
    var open by remember { mutableStateOf<ScreenshotEntity?>(null) }
    var compareMode by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<ScreenshotEntity>() }
    var comparison by remember { mutableStateOf<Triple<ImageBitmap, ImageBitmap, ImageDiff.Result>?>(null) }

    Column {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${shots.size} screenshots", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            FilterChip(selected = compareMode, onClick = { compareMode = !compareMode; picked.clear() }, label = { Text(if (compareMode) "Pick 2 to compare (${picked.size}/2)" else "Compare") })
        }
        if (shots.isEmpty()) {
            EmptyState("No screenshots", "Start a capture session in the Testing Center, then capture or run an app test.")
            return@Column
        }
        LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shots, key = { it.id }) { s ->
                val bmp = remember(s.filePath) { BitmapFactory.decodeFile(s.filePath, BitmapFactory.Options().apply { inSampleSize = 6 })?.asImageBitmap() }
                val isPicked = picked.any { it.id == s.id }
                Column(
                    Modifier.clickable {
                        if (compareMode) {
                            if (isPicked) picked.removeAll { it.id == s.id } else if (picked.size < 2) picked.add(s)
                            if (picked.size == 2) {
                                val a = picked[0]
                                val b = picked[1]
                                scope.launch {
                                    comparison = withContext(Dispatchers.Default) {
                                        val ba = BitmapFactory.decodeFile(a.filePath)
                                        val bb = BitmapFactory.decodeFile(b.filePath)
                                        if (ba == null || bb == null) null else Triple(ba.asImageBitmap(), bb.asImageBitmap(), ImageDiff.compare(ba, bb))
                                    }
                                    picked.clear()
                                    compareMode = false
                                }
                            }
                        } else {
                            open = s
                        }
                    }
                        .border(if (isPicked) 2.dp else 0.dp, GravityColors.Cyan, RoundedCornerShape(8.dp)),
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.55f).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))) {
                        if (bmp != null) Image(bmp, s.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Text(s.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    if (s.analysis != null) Text("analysed", style = MaterialTheme.typography.labelSmall, color = GravityColors.Purple)
                }
            }
        }
    }

    open?.let { s ->
        var analysing by remember { mutableStateOf(false) }
        val bmp = remember(s.filePath) { BitmapFactory.decodeFile(s.filePath)?.asImageBitmap() }
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(s.label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (bmp != null) Image(bmp, s.label, modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), contentScale = ContentScale.Fit)
                    KeyValue("Captured", formatTimeFull(s.createdAt))
                    KeyValue("Size", "${s.width}×${s.height}")
                    s.packageName?.let { KeyValue("App", it) }
                    if (analysing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    s.analysis?.let { RichText(it) }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(s.filePath))
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share screenshot"))
                    }) { Text("Share") }
                    TextButton(enabled = !analysing, onClick = {
                        analysing = true
                        scope.launch {
                            try {
                                val text = c.appTests.analyze(s, providers.firstOrNull { it.enabled && it.kind != "LOCAL" }?.id, "")
                                open = s.copy(analysis = text)
                            } catch (e: Exception) {
                                toast(context, "Analysis failed: ${e.message}")
                            } finally {
                                analysing = false
                            }
                        }
                    }) { Text("AI analyse") }
                    TextButton(onClick = {
                        scope.launch { c.screenshots.delete(s) }
                        open = null
                    }) { Text("Delete", color = GravityColors.Red) }
                    TextButton(onClick = { open = null }) { Text("Close") }
                }
            },
        )
    }

    comparison?.let { (a, b, diff) ->
        AlertDialog(
            onDismissRequest = { comparison = null },
            title = { Text("Visual comparison") },
            text = {
                Column {
                    Text("%.2f%% of pixels differ (compared at %d×%d)".format(diff.differentPercent, diff.comparedWidth, diff.comparedHeight), color = if (diff.differentPercent > 1) GravityColors.Amber else GravityColors.Green)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Image(a, "Before", Modifier.weight(1f), contentScale = ContentScale.Fit)
                        Image(b, "After", Modifier.weight(1f), contentScale = ContentScale.Fit)
                        Image(diff.diffImage.asImageBitmap(), "Difference", Modifier.weight(1f), contentScale = ContentScale.Fit)
                    }
                    Text("Before · After · Differences (red)", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = { TextButton(onClick = { comparison = null }) { Text("Close") } },
        )
    }
}

/**
 * Real WebView. Project previews are served from app storage through WebViewAssetLoader
 * (https://appassets.androidplatform.net/project/<id>/...), so relative URLs, fetch() and
 * ES modules behave like a web server. Console output and load errors are captured.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(initialUrl: String?) {
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val projects by c.db.projects().observeActive().collectAsState(initial = emptyList())
    val network by c.network.state.collectAsState()
    var address by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    val console = remember { mutableStateListOf<String>() }
    var showConsole by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var desktop by remember { mutableStateOf(false) }

    val loader = remember {
        WebViewAssetLoader.Builder()
            .addPathHandler("/project/", WebViewAssetLoader.InternalStoragePathHandler(context, c.workspace.workspacesRoot))
            .build()
    }

    fun resolve(input: String): String {
        val t = input.trim()
        return when {
            t.startsWith("gravity-project:") -> {
                val rest = t.removePrefix("gravity-project:")
                val id = rest.substringBefore('/')
                val path = rest.substringAfter('/', "index.html").ifBlank { "index.html" }
                val p = projects.firstOrNull { it.id == id }
                if (p == null) "about:blank" else "https://appassets.androidplatform.net/project/${File(p.rootPath).name}/$path"
            }
            t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(t)
        }
    }

    var initialLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(initialUrl, projects, webView) {
        val wv = webView ?: return@LaunchedEffect
        if (initialLoaded || initialUrl == null) return@LaunchedEffect
        if (initialUrl.startsWith("gravity-project:") && projects.isEmpty()) return@LaunchedEffect
        address = resolve(initialUrl)
        wv.loadUrl(address)
        initialLoaded = true
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { webView?.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            OutlinedTextField(
                address, { address = it }, singleLine = true, placeholder = { Text("URL or search") }, modifier = Modifier.weight(1f),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { address = resolve(address); webView?.loadUrl(address) }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
            )
            IconButton(onClick = { webView?.reload() }) { Icon(Icons.Filled.Refresh, "Reload") }
            IconButton(onClick = { showConsole = !showConsole }) { Icon(Icons.Filled.Terminal, "Console", tint = if (console.any { it.startsWith("ERROR") }) GravityColors.Red else MaterialTheme.colorScheme.onSurface) }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            projects.filter { it.type == "WEB" || it.type == "GAME" || it.type == "APP" }.take(4).forEach { p ->
                FilterChip(selected = false, onClick = { address = resolve("gravity-project:${p.id}"); webView?.loadUrl(address) }, label = { Text(p.name, maxLines = 1) })
            }
            FilterChip(selected = desktop, onClick = {
                desktop = !desktop
                webView?.settings?.apply { useWideViewPort = desktop; loadWithOverviewMode = desktop }
                webView?.reload()
            }, label = { Text("Wide") })
        }
        if (!network.online) Text("Offline: only local project previews will load.", color = GravityColors.Amber, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp))
        if (loading) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            loading = true
                            address = url
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            loading = false
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            console.add("ERROR load ${request.url}: ${error.description}")
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                        }
                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            console.add("${m.messageLevel()} ${m.sourceId().substringAfterLast('/')}:${m.lineNumber()} ${m.message()}")
                            if (console.size > 500) console.removeAt(0)
                            return true
                        }
                    }
                    webView = this
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        if (showConsole) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentPadding = PaddingValues(8.dp)) {
                item {
                    Row {
                        Text("Console (${console.size})", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { console.clear() }) { Text("Clear") }
                    }
                }
                items(console.toList()) { line ->
                    Text(line, fontFamily = MonoFamily, fontSize = 11.sp, color = if (line.startsWith("ERROR")) GravityColors.Red else if (line.startsWith("WARNING")) GravityColors.Amber else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
