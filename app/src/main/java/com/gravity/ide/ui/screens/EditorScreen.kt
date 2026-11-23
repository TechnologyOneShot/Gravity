@file:OptIn(ExperimentalMaterial3Api::class)

package com.gravity.ide.ui.screens

import com.gravity.ide.ui.components.safeTabIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.FormatIndentIncrease
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gravity.ide.ai.LlmMessage
import com.gravity.ide.ai.LlmRequest
import com.gravity.ide.ai.ReasoningMode
import com.gravity.ide.ai.Role
import com.gravity.ide.ai.complete
import com.gravity.ide.core.Language
import com.gravity.ide.core.LineDiff
import com.gravity.ide.core.PathGuard
import com.gravity.ide.core.SecretScanner
import com.gravity.ide.core.SyntaxHighlighter
import com.gravity.ide.core.Token
import com.gravity.ide.core.TokenType
import com.gravity.ide.data.db.FileVersionEntity
import com.gravity.ide.data.db.ProjectEntity
import com.gravity.ide.data.settings.AppSettings
import com.gravity.ide.di.AppContainer
import com.gravity.ide.ui.LocalAppContainer
import com.gravity.ide.ui.components.EmptyState
import com.gravity.ide.ui.components.MenuAction
import com.gravity.ide.ui.components.OverflowMenu
import com.gravity.ide.ui.components.TextInputDialog
import com.gravity.ide.ui.components.formatTime
import com.gravity.ide.ui.gravityViewModel
import com.gravity.ide.ui.theme.CodeColors
import com.gravity.ide.ui.theme.GravityColors
import com.gravity.ide.ui.theme.LocalCodeColors
import com.gravity.ide.ui.theme.MonoFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.min

data class EditorTab(
    val relPath: String,
    val language: Language,
    val value: TextFieldValue,
    val saved: String,
    val undo: List<TextFieldValue> = emptyList(),
    val redo: List<TextFieldValue> = emptyList(),
    val lastSnapshotAt: Long = 0,
    val readOnly: Boolean = false,
) {
    val dirty: Boolean get() = value.text != saved
    val name: String get() = relPath.substringAfterLast('/')
}

data class Diagnostic(val line: Int, val message: String)

class EditorViewModel(private val c: AppContainer, val projectId: String) : ViewModel() {
    val project = MutableStateFlow<ProjectEntity?>(null)
    val tabs = MutableStateFlow<List<EditorTab>>(emptyList())
    val active = MutableStateFlow(0)
    val message = MutableStateFlow<String?>(null)
    val diagnostics = MutableStateFlow<List<Diagnostic>>(emptyList())
    val aiBusy = MutableStateFlow(false)
    private var autoSaveJob: Job? = null
    private var settings = AppSettings()

    init {
        viewModelScope.launch {
            project.value = c.db.projects().get(projectId)
            settings = c.settings.current()
        }
    }

    private val root: File? get() = project.value?.let { File(it.rootPath) }

    fun open(relPath: String) {
        val existing = tabs.value.indexOfFirst { it.relPath == relPath }
        if (existing >= 0) {
            active.value = existing
            return
        }
        viewModelScope.launch {
            val p = project.value ?: c.db.projects().get(projectId)?.also { project.value = it } ?: return@launch
            try {
                val f = PathGuard.resolveInside(File(p.rootPath), relPath)
                val (text, readOnly) = withContext(Dispatchers.IO) {
                    when {
                        !f.isFile -> "" to false
                        f.length() > 5_000_000 -> throw IllegalStateException("File is larger than 5 MB; open it in the terminal instead")
                        !c.workspace.isProbablyText(f) -> throw IllegalStateException("Binary file — cannot be edited as text")
                        else -> f.readText() to false
                    }
                }
                val tab = EditorTab(relPath, Language.forFileName(f.name), TextFieldValue(text), text, readOnly = readOnly)
                tabs.value = tabs.value + tab
                active.value = tabs.value.size - 1
                diagnostics.value = emptyList()
            } catch (e: Exception) {
                message.value = e.message
            }
        }
    }

    fun close(index: Int) {
        val list = tabs.value.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        tabs.value = list
        active.value = active.value.coerceAtMost(list.size - 1).coerceAtLeast(0)
    }

    private fun updateTab(index: Int, transform: (EditorTab) -> EditorTab) {
        val list = tabs.value.toMutableList()
        if (index !in list.indices) return
        list[index] = transform(list[index])
        tabs.value = list
    }

    fun onEdit(index: Int, incoming: TextFieldValue) {
        updateTab(index) { tab ->
            if (tab.readOnly) return@updateTab tab
            val value = autoIndent(tab.value, incoming)
            if (value.text == tab.value.text) return@updateTab tab.copy(value = value)
            val now = System.currentTimeMillis()
            val snapshot = now - tab.lastSnapshotAt > 700 || value.text.length - tab.value.text.length == 1 && value.text.getOrNull(value.selection.start - 1) == '\n'
            tab.copy(
                value = value,
                undo = if (snapshot) (tab.undo + tab.value).takeLast(200) else tab.undo,
                redo = emptyList(),
                lastSnapshotAt = if (snapshot) now else tab.lastSnapshotAt,
            )
        }
        scheduleAutoSave(index)
    }

    /** Carries the previous line's indentation onto a new line, plus one level after an opener. */
    private fun autoIndent(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
        if (new.text.length != old.text.length + 1) return new
        val pos = new.selection.start
        if (pos <= 0 || new.text[pos - 1] != '\n' || !new.selection.collapsed) return new
        val lineStart = new.text.lastIndexOf('\n', pos - 2).let { if (it < 0) 0 else it + 1 }
        val prevLine = new.text.substring(lineStart, pos - 1)
        val indent = prevLine.takeWhile { it == ' ' || it == '\t' }
        val extra = if (prevLine.trimEnd().let { it.endsWith("{") || it.endsWith("(") || it.endsWith("[") || it.endsWith(":") }) "    " else ""
        val insert = indent + extra
        if (insert.isEmpty()) return new
        val text = new.text.substring(0, pos) + insert + new.text.substring(pos)
        return TextFieldValue(text, TextRange(pos + insert.length))
    }

    private fun scheduleAutoSave(index: Int) {
        if (!settings.autoSave) return
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(2500)
            save(index, quiet = true)
        }
    }

    fun undo(index: Int) = updateTab(index) { t ->
        val prev = t.undo.lastOrNull() ?: return@updateTab t
        t.copy(value = prev, undo = t.undo.dropLast(1), redo = t.redo + t.value)
    }

    fun redo(index: Int) = updateTab(index) { t ->
        val next = t.redo.lastOrNull() ?: return@updateTab t
        t.copy(value = next, redo = t.redo.dropLast(1), undo = t.undo + t.value)
    }

    fun replaceText(index: Int, text: String, cursor: Int = 0) = updateTab(index) { t ->
        t.copy(value = TextFieldValue(text, TextRange(cursor.coerceIn(0, text.length))), undo = (t.undo + t.value).takeLast(200), redo = emptyList())
    }

    fun setSelection(index: Int, start: Int, end: Int = start) = updateTab(index) { t ->
        t.copy(value = t.value.copy(selection = TextRange(start.coerceIn(0, t.value.text.length), end.coerceIn(0, t.value.text.length))))
    }

    fun save(index: Int, quiet: Boolean = false) {
        val tab = tabs.value.getOrNull(index) ?: return
        val p = project.value ?: return
        if (!tab.dirty) return
        val text = tab.value.text
        viewModelScope.launch {
            try {
                val r = File(p.rootPath)
                val f = PathGuard.resolveInside(r, tab.relPath)
                c.workspace.withFileLock(f) {
                    c.checkpoints.snapshot(p.id, r, f, "editor", note = if (quiet) "Auto-save" else "Save")
                    withContext(Dispatchers.IO) {
                        f.parentFile?.mkdirs()
                        f.writeText(text)
                    }
                }
                updateTab(tabs.value.indexOfFirst { it.relPath == tab.relPath }) { it.copy(saved = text) }
                diagnostics.value = diagnose(text, tab.language)
                if (!quiet) message.value = "Saved ${tab.name}"
            } catch (e: Exception) {
                message.value = "Save failed: ${e.message}"
            }
        }
    }

    fun format(index: Int) {
        val tab = tabs.value.getOrNull(index) ?: return
        val formatted = try {
            when (tab.language) {
                Language.JSON -> tab.value.text.trim().let { if (it.startsWith("[")) JSONArray(it).toString(2) else JSONObject(it).toString(2) }
                Language.PYTHON, Language.YAML, Language.MARKDOWN, Language.PLAIN -> {
                    message.value = "No formatter for ${tab.language.displayName}: whitespace was trimmed only"
                    tab.value.text.lines().joinToString("\n") { it.trimEnd() }
                }
                else -> reindent(tab.value.text)
            }
        } catch (e: Exception) {
            message.value = "Format failed: ${e.message}"
            return
        }
        replaceText(index, formatted, min(tab.value.selection.start, formatted.length))
    }

    /** Brace-depth re-indentation for C-like languages and markup (not a full formatter). */
    private fun reindent(text: String): String {
        var depth = 0
        val out = StringBuilder()
        for (raw in text.lines()) {
            val line = raw.trim()
            val closesFirst = line.startsWith("}") || line.startsWith(")") || line.startsWith("]") || line.startsWith("</")
            if (closesFirst) depth = (depth - 1).coerceAtLeast(0)
            if (line.isNotEmpty()) out.append("    ".repeat(depth)).append(line)
            out.append('\n')
            val opens = line.count { it == '{' || it == '(' || it == '[' } + Regex("<[A-Za-z][^>]*[^/]>").findAll(line).count()
            val closes = line.count { it == '}' || it == ')' || it == ']' } + Regex("</[A-Za-z]").findAll(line).count()
            depth = (depth + opens - closes + if (closesFirst) 1 else 0).coerceAtLeast(0)
        }
        return out.toString().trimEnd('\n') + if (text.endsWith("\n")) "\n" else ""
    }

    fun runDiagnostics(index: Int) {
        val tab = tabs.value.getOrNull(index) ?: return
        viewModelScope.launch { diagnostics.value = withContext(Dispatchers.Default) { diagnose(tab.value.text, tab.language) } }
    }

    private fun diagnose(text: String, language: Language): List<Diagnostic> = when (language) {
        Language.JSON -> try {
            val t = text.trim()
            if (t.isNotEmpty()) { if (t.startsWith("[")) JSONArray(t) else JSONObject(t) }
            emptyList()
        } catch (e: Exception) {
            val pos = Regex("character (\\d+)").find(e.message ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0
            listOf(Diagnostic(lineOf(text, pos), e.message ?: "Invalid JSON"))
        }
        Language.XML, Language.HTML -> if (language == Language.HTML) bracketCheck(text, language) else try {
            val parser = android.util.Xml.newPullParser()
            parser.setInput(text.reader())
            while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) Unit
            emptyList()
        } catch (e: Exception) {
            val line = (e as? org.xmlpull.v1.XmlPullParserException)?.lineNumber ?: 1
            listOf(Diagnostic(line.coerceAtLeast(1), e.message ?: "Invalid XML"))
        }
        Language.PLAIN, Language.MARKDOWN, Language.YAML -> emptyList()
        else -> bracketCheck(text, language)
    }

    private fun bracketCheck(text: String, language: Language): List<Diagnostic> {
        val tokens = SyntaxHighlighter.tokenize(text, language)
        val skip = tokens.filter { it.type == TokenType.STRING || it.type == TokenType.COMMENT }
        val stack = ArrayDeque<Pair<Char, Int>>()
        val pairs = mapOf(')' to '(', ']' to '[', '}' to '{')
        var t = 0
        for (i in text.indices) {
            while (t < skip.size && skip[t].end <= i) t++
            if (t < skip.size && i >= skip[t].start && i < skip[t].end) continue
            val ch = text[i]
            if (ch == '(' || ch == '[' || ch == '{') stack.addLast(ch to i)
            else if (ch in pairs) {
                val open = stack.removeLastOrNull()
                if (open == null || open.first != pairs[ch]) return listOf(Diagnostic(lineOf(text, i), "Unmatched '$ch'"))
            }
        }
        return stack.lastOrNull()?.let { listOf(Diagnostic(lineOf(text, it.second), "Unclosed '${it.first}'")) } ?: emptyList()
    }

    private fun lineOf(text: String, pos: Int): Int = text.substring(0, pos.coerceIn(0, text.length)).count { it == '\n' } + 1

    suspend fun history(relPath: String): List<FileVersionEntity> = c.db.fileVersions().forFile(projectId, relPath)

    fun readVersion(v: FileVersionEntity): String = c.checkpoints.readBlob(v) ?: ""

    fun restoreVersion(index: Int, v: FileVersionEntity) {
        val text = readVersion(v)
        replaceText(index, text)
        message.value = "Loaded version from ${formatTime(v.createdAt)} — save to apply"
    }

    /** AI completion at the cursor through the configured provider (local-only projects use the local model). */
    fun aiComplete(index: Int) {
        val tab = tabs.value.getOrNull(index) ?: return
        val p = project.value ?: return
        aiBusy.value = true
        viewModelScope.launch {
            try {
                val resolved = c.providers.resolve(if (p.localOnly) com.gravity.ide.ai.local.LocalLlamaProvider.ID else null, null)
                if (p.localOnly && !resolved.provider.isLocal) throw IllegalStateException("Local-only project")
                val text = tab.value.text
                val cursor = tab.value.selection.start
                var prefix = text.substring(maxOf(0, cursor - 4000), cursor)
                var suffix = text.substring(cursor, min(text.length, cursor + 1500))
                if (!resolved.provider.isLocal) {
                    prefix = SecretScanner.redact(prefix).first
                    suffix = SecretScanner.redact(suffix).first
                }
                val prompt = "File: ${tab.relPath} (${tab.language.displayName}). Complete the code at <CURSOR>. " +
                    "Reply with ONLY the code to insert, no explanations and no markdown fences.\n\n$prefix<CURSOR>$suffix"
                val result = resolved.provider.complete(
                    LlmRequest(resolved.model, listOf(LlmMessage(Role.USER, prompt)), reasoning = ReasoningMode.FAST, maxOutputTokens = 512, temperature = 0.2)
                )
                c.providers.recordUsage(resolved.entity, result.inputTokens, result.outputTokens)
                val insert = result.text.replace(Regex("^```[a-zA-Z]*\\n|\\n?```$"), "")
                val current = tabs.value.getOrNull(index) ?: return@launch
                val t = current.value.text
                val at = current.value.selection.start.coerceIn(0, t.length)
                replaceText(index, t.substring(0, at) + insert + t.substring(at), at + insert.length)
                message.value = "Inserted ${insert.length} chars from ${resolved.model}"
            } catch (e: Exception) {
                message.value = "AI completion failed: ${e.message}"
            } finally {
                aiBusy.value = false
            }
        }
    }
}

/** Visual transformation: syntax colours, bracket match and search highlights (offsets unchanged). */
private class CodeTransformation(
    private val tokens: List<Token>,
    private val colors: CodeColors,
    private val bracket: Pair<Int, Int>?,
    private val searchHits: List<IntRange>,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val len = text.length
        val b = AnnotatedString.Builder(text.text)
        for (t in tokens) {
            if (t.start >= len) continue
            val end = min(t.end, len)
            val style = when (t.type) {
                TokenType.KEYWORD -> SpanStyle(color = colors.keyword)
                TokenType.TYPE -> SpanStyle(color = colors.type)
                TokenType.STRING -> SpanStyle(color = colors.string)
                TokenType.NUMBER -> SpanStyle(color = colors.number)
                TokenType.COMMENT -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
                TokenType.ANNOTATION -> SpanStyle(color = colors.annotation)
                TokenType.TAG -> SpanStyle(color = colors.tag)
                TokenType.ATTRIBUTE -> SpanStyle(color = colors.attribute)
                TokenType.PUNCTUATION -> SpanStyle(color = colors.punctuation)
                TokenType.HEADING -> SpanStyle(color = colors.heading)
                TokenType.PLAIN -> null
            }
            if (style != null) b.addStyle(style, t.start, end)
        }
        bracket?.let { (a, z) ->
            if (a in 0 until len) b.addStyle(SpanStyle(background = colors.bracketMatch), a, a + 1)
            if (z in 0 until len) b.addStyle(SpanStyle(background = colors.bracketMatch), z, z + 1)
        }
        for (r in searchHits) if (r.first < len) b.addStyle(SpanStyle(background = colors.search), r.first, min(r.last + 1, len))
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }
}

@Composable
fun EditorScreen(projectId: String, path: String?, go: (String) -> Unit) {
    val vm = gravityViewModel(key = "editor-$projectId") { EditorViewModel(it, projectId) }
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tabs by vm.tabs.collectAsState()
    val activeIndex by vm.active.collectAsState()
    val message by vm.message.collectAsState()
    val diagnostics by vm.diagnostics.collectAsState()
    val aiBusy by vm.aiBusy.collectAsState()
    val settings by c.settings.settings.collectAsState(initial = AppSettings())
    val colors = LocalCodeColors.current
    var findOpen by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var gotoOpen by remember { mutableStateOf(false) }
    var diffOpen by remember { mutableStateOf<Pair<String, String>?>(null) }
    var historyOpen by remember { mutableStateOf<List<FileVersionEntity>?>(null) }

    LaunchedEffect(path) { if (!path.isNullOrBlank()) vm.open(path) }
    LaunchedEffect(message) { message?.let { toast(context, it); vm.message.value = null } }

    if (tabs.isEmpty()) {
        EmptyState("No file open", "Open a file from the Files screen.", action = { TextButton(onClick = { go(com.gravity.ide.ui.Routes.files(projectId)) }) { Text("Browse files") } })
        return
    }
    val index = activeIndex.coerceIn(0, tabs.size - 1)
    val tab = tabs[index]
    val fontSize = settings.editorFontSize.sp
    val lineHeight = (settings.editorFontSize * 1.5f).sp
    val textStyle = androidx.compose.ui.text.TextStyle(fontFamily = MonoFamily, fontSize = fontSize, lineHeight = lineHeight, color = colors.plain)

    var tokens by remember(tab.relPath) { mutableStateOf<List<Token>>(emptyList()) }
    val text = tab.value.text
    LaunchedEffect(text, tab.language) {
        delay(120)
        tokens = withContext(Dispatchers.Default) { SyntaxHighlighter.tokenize(text, tab.language) }
    }
    val cursor = tab.value.selection.start
    val bracket = remember(tokens, cursor, text) {
        val at = listOf(cursor, cursor - 1).firstOrNull { it in text.indices && text[it] in "()[]{}" }
        at?.let { a -> SyntaxHighlighter.matchingBracket(text, a, tokens).takeIf { it >= 0 }?.let { a to it } }
    }
    val searchHits = remember(findText, text, findOpen) {
        if (!findOpen || findText.isEmpty()) emptyList() else Regex(Regex.escape(findText), RegexOption.IGNORE_CASE).findAll(text).take(2000).map { it.range }.toList()
    }
    val transformation = remember(tokens, bracket, searchHits, colors) { CodeTransformation(tokens, colors, bracket, searchHits) }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val density = LocalDensity.current
    val lineCount = text.count { it == '\n' } + 1
    val currentLine = text.substring(0, cursor.coerceIn(0, text.length)).count { it == '\n' } + 1

    // Word completion from identifiers already in the file.
    val prefix = text.substring(0, cursor.coerceIn(0, text.length)).takeLastWhile { it.isLetterOrDigit() || it == '_' }
    val suggestions = remember(prefix, tokens.size) {
        if (prefix.length < 2) emptyList() else Regex("[A-Za-z_][A-Za-z0-9_]{2,}").findAll(text).map { it.value }
            .filter { it.startsWith(prefix) && it != prefix }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(6).map { it.key }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScrollableTabRow(selectedTabIndex = index, indicator = safeTabIndicator(index), edgePadding = 4.dp, containerColor = MaterialTheme.colorScheme.background) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = i == index, onClick = { vm.active.value = i }, text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((if (t.dirty) "● " else "") + t.name, maxLines = 1)
                        Icon(Icons.Filled.Close, "Close tab", modifier = Modifier.padding(start = 6.dp).size(14.dp).clickable { vm.close(i) })
                    }
                })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.save(index) }, enabled = tab.dirty) { Icon(Icons.Filled.Save, "Save", tint = if (tab.dirty) GravityColors.Cyan else MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = { vm.undo(index) }, enabled = tab.undo.isNotEmpty()) { Icon(Icons.Filled.Undo, "Undo") }
            IconButton(onClick = { vm.redo(index) }, enabled = tab.redo.isNotEmpty()) { Icon(Icons.Filled.Redo, "Redo") }
            IconButton(onClick = { findOpen = !findOpen }) { Icon(Icons.Filled.FindReplace, "Find and replace") }
            IconButton(onClick = { vm.aiComplete(index) }, enabled = !aiBusy) { Icon(Icons.Filled.AutoAwesome, "AI complete", tint = GravityColors.Purple) }
            Box(Modifier.weight(1f))
            Text("Ln $currentLine/$lineCount · ${tab.language.displayName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OverflowMenu(
                listOf(
                    MenuAction("Go to line", Icons.Filled.VerticalAlignCenter) { gotoOpen = true },
                    MenuAction("Format / re-indent", Icons.Filled.FormatIndentIncrease) { vm.format(index) },
                    MenuAction("Check diagnostics", Icons.Filled.Rule) { vm.runDiagnostics(index) },
                    MenuAction("Diff vs saved", Icons.Filled.Compare) { diffOpen = tab.saved to tab.value.text },
                    MenuAction("Version history", Icons.Filled.History) { scope.launch { historyOpen = vm.history(tab.relPath) } },
                )
            )
        }
        if (aiBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (findOpen) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(findText, { findText = it }, label = { Text("Find (${searchHits.size})") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(replaceText, { replaceText = it }, label = { Text("Replace") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = {
                    val next = searchHits.firstOrNull { it.first >= cursor + 1 } ?: searchHits.firstOrNull()
                    if (next != null) {
                        vm.setSelection(index, next.first, next.last + 1)
                        scope.launch { vScroll.animateScrollTo(with(density) { ((lineOf(text, next.first) - 3).coerceAtLeast(0) * lineHeight.toPx()).toInt() }) }
                    }
                }) { Text("Next") }
                TextButton(onClick = {
                    val sel = tab.value.selection
                    if (!sel.collapsed && text.substring(sel.min, sel.max).equals(findText, true)) {
                        vm.replaceText(index, text.substring(0, sel.min) + replaceText + text.substring(sel.max), sel.min + replaceText.length)
                    }
                }) { Text("Replace") }
                TextButton(onClick = {
                    if (findText.isNotEmpty()) vm.replaceText(index, text.replace(findText, replaceText, ignoreCase = true), cursor)
                }) { Text("Replace all") }
            }
        }
        if (diagnostics.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(GravityColors.Red.copy(alpha = 0.1f)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                diagnostics.take(3).forEach { d ->
                    Text("Line ${d.line}: ${d.message}", style = MaterialTheme.typography.bodySmall, color = GravityColors.Red, modifier = Modifier.clickable {
                        val off = offsetOfLine(text, d.line)
                        vm.setSelection(index, off)
                        scope.launch { vScroll.animateScrollTo(with(density) { ((d.line - 3).coerceAtLeast(0) * lineHeight.toPx()).toInt() }) }
                    })
                }
                Text("Basic diagnostics (syntax/bracket checks, no language server)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(colors.background)) {
            val viewport = maxWidth
            Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
                Text(
                    (1..lineCount).joinToString("\n"),
                    style = textStyle.copy(color = colors.gutter, textAlign = TextAlign.End),
                    modifier = Modifier.padding(start = 6.dp, end = 8.dp, top = 6.dp).widthIn(min = 28.dp),
                )
                Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
                    BasicTextField(
                        value = tab.value,
                        onValueChange = { vm.onEdit(index, it) },
                        textStyle = textStyle,
                        cursorBrush = SolidColor(GravityColors.Cyan),
                        visualTransformation = transformation,
                        readOnly = tab.readOnly,
                        modifier = Modifier.widthIn(min = viewport - 48.dp).heightIn(min = 400.dp).padding(top = 6.dp, end = 24.dp, bottom = 200.dp),
                    )
                }
            }
        }
        if (suggestions.isNotEmpty()) {
            LazyRow(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(suggestions) { s ->
                    AssistChip(onClick = {
                        val at = cursor.coerceIn(0, text.length)
                        val start = at - prefix.length
                        vm.replaceText(index, text.substring(0, start) + s + text.substring(at), start + s.length)
                    }, label = { Text(s, fontFamily = MonoFamily, fontSize = 12.sp) })
                }
            }
        }
    }

    if (gotoOpen) TextInputDialog("Go to line", "Line number (1–$lineCount)", confirmLabel = "Go", onConfirm = { v ->
        val line = v.toIntOrNull()?.coerceIn(1, lineCount) ?: return@TextInputDialog
        vm.setSelection(index, offsetOfLine(text, line))
        scope.launch { vScroll.animateScrollTo(with(density) { ((line - 3).coerceAtLeast(0) * lineHeight.toPx()).toInt() }) }
    }, onDismiss = { gotoOpen = false })

    diffOpen?.let { (before, after) -> DiffDialog("Changes in ${tab.name}", before, after, onDismiss = { diffOpen = null }) }

    historyOpen?.let { versions ->
        AlertDialog(
            onDismissRequest = { historyOpen = null },
            title = { Text("History · ${tab.name}") },
            text = {
                if (versions.isEmpty()) Text("No saved versions yet. A version is recorded before every save and every agent edit.") else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(versions, key = { it.id }) { v ->
                        Column(Modifier.fillMaxWidth().clickable {
                            historyOpen = null
                            diffOpen = vm.readVersion(v) to tab.value.text
                        }.padding(vertical = 8.dp)) {
                            Text("${formatTime(v.createdAt)} · ${v.source}${v.agentRole?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodyMedium)
                            Text(listOfNotNull(v.model, v.note.ifBlank { null }, if (v.blobPath == null) "file did not exist" else null).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { historyOpen = null; vm.restoreVersion(index, v) }) { Text("Load this version") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { historyOpen = null }) { Text("Close") } },
        )
    }
}

private fun lineOf(text: String, pos: Int) = text.substring(0, pos.coerceIn(0, text.length)).count { it == '\n' } + 1

private fun offsetOfLine(text: String, line: Int): Int {
    var l = 1
    for (i in text.indices) {
        if (l == line) return i
        if (text[i] == '\n') l++
    }
    return text.length
}

@Composable
fun DiffDialog(title: String, before: String, after: String, onDismiss: () -> Unit, extraAction: (@Composable () -> Unit)? = null) {
    val lines = remember(before, after) { LineDiff.diff(before, after) }
    val stats = remember(lines) { LineDiff.stats(lines) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("+${stats.added}  −${stats.removed}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                if (stats.added + stats.removed == 0) Text("No differences.") else DiffView(lines, Modifier.heightIn(max = 460.dp))
            }
        },
        confirmButton = { Row { extraAction?.invoke(); TextButton(onClick = onDismiss) { Text("Close") } } },
    )
}

@Composable
fun DiffView(lines: List<LineDiff.Line>, modifier: Modifier = Modifier) {
    // Show changed lines with 2 lines of context.
    val visible = remember(lines) {
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { i, l -> if (l.op != LineDiff.Op.SAME) for (j in (i - 2)..(i + 2)) if (j in lines.indices) keep[j] = true }
        lines.indices.filter { keep[it] }
    }
    LazyColumn(modifier.horizontalScroll(rememberScrollState())) {
        items(visible) { i ->
            val l = lines[i]
            val (bg, sign) = when (l.op) {
                LineDiff.Op.ADD -> GravityColors.Green.copy(alpha = 0.15f) to "+"
                LineDiff.Op.DEL -> GravityColors.Red.copy(alpha = 0.15f) to "−"
                LineDiff.Op.SAME -> Color.Transparent to " "
            }
            Text(
                "${(l.oldNo ?: "").toString().padStart(4)} ${(l.newNo ?: "").toString().padStart(4)} $sign ${l.text}",
                fontFamily = MonoFamily, fontSize = 11.sp, softWrap = false,
                modifier = Modifier.background(bg).padding(horizontal = 4.dp),
            )
        }
    }
}
