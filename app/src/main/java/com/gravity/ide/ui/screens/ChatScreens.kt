@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.gravity.ide.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gravity.ide.ai.ImagePart
import com.gravity.ide.ai.LlmEvent
import com.gravity.ide.ai.LlmMessage
import com.gravity.ide.ai.LlmRequest
import com.gravity.ide.ai.ReasoningMapper
import com.gravity.ide.ai.ReasoningMode
import com.gravity.ide.ai.Role
import com.gravity.ide.ai.WebSource
import com.gravity.ide.ai.local.LocalLlamaProvider
import com.gravity.ide.core.SecretScanner
import com.gravity.ide.data.EventCategory
import com.gravity.ide.data.EventLevel
import com.gravity.ide.data.db.ChatEntity
import com.gravity.ide.data.db.MessageEntity
import com.gravity.ide.data.db.ProviderEntity
import com.gravity.ide.di.AppContainer
import com.gravity.ide.ui.LocalAppContainer
import com.gravity.ide.ui.Routes
import com.gravity.ide.ui.components.ConfirmDialog
import com.gravity.ide.ui.components.EmptyState
import com.gravity.ide.ui.components.MenuAction
import com.gravity.ide.ui.components.MessageBanner
import com.gravity.ide.ui.components.MonoBlock
import com.gravity.ide.ui.components.OverflowMenu
import com.gravity.ide.ui.components.Panel
import com.gravity.ide.ui.components.Selector
import com.gravity.ide.ui.components.TextInputDialog
import com.gravity.ide.ui.components.formatBytes
import com.gravity.ide.ui.components.relativeTime
import com.gravity.ide.ui.gravityViewModel
import com.gravity.ide.ui.theme.GravityColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/** A file the user attached to the next message (parsed safely on-device). */
data class Attachment(val name: String, val mime: String, val size: Long, val kind: String, val text: String? = null, val images: List<ImagePart> = emptyList())

object AttachmentReader {
    private const val MAX_TEXT = 512 * 1024
    private const val MAX_IMAGE = 15L * 1024 * 1024
    private const val MAX_PDF_TEXT = 400_000

    fun read(context: Context, uri: Uri): Attachment {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) {
                name = cur.getString(0) ?: name
                if (!cur.isNull(1)) size = cur.getLong(1)
            }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val lower = name.lowercase()
        return when {
            mime.startsWith("image/") -> {
                if (size > MAX_IMAGE) throw IllegalArgumentException("$name is too large (${formatBytes(size)}); limit ${formatBytes(MAX_IMAGE)}")
                // Decoded at a reduced size (it is sent at 1600 px at most): a 50 MP camera photo at full
                // size needs about 200 MB and can close the app.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
                val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                    ?: throw IllegalArgumentException("Cannot decode image $name")
                Attachment(name, mime, size, "image", images = listOf(ImagePart("image/jpeg", jpeg(bmp, 1600))))
            }
            mime == "application/pdf" || lower.endsWith(".pdf") -> {
                // Text for every AI (phone models read text): the PDF's text layer, or on-device OCR
                // (Hindi too) when it has none; the first pages also go as pictures to cloud AI.
                val pdf = com.gravity.ide.files.PdfReader.read(context, uri)
                val body = if (pdf.text.length > MAX_PDF_TEXT) pdf.text.take(MAX_PDF_TEXT) + "\n[… the rest of the PDF is longer than ${MAX_PDF_TEXT / 1000}k characters and was left out]" else pdf.text
                val header = "PDF $name: ${pdf.pages} page(s); text from ${pdf.method}" +
                    (if (pdf.pagesRead < pdf.pages) " (first ${pdf.pagesRead} pages)" else "") + "."
                Attachment(
                    name, mime, size, "pdf",
                    text = if (body.isBlank()) "$header No text could be read from it." else "$header\n$body",
                    images = pdf.previews.map { ImagePart("image/jpeg", jpeg(it, 1600)) },
                )
            }
            mime == "application/zip" || lower.endsWith(".zip") -> {
                val sb = StringBuilder("ZIP archive $name contents:\n")
                var budget = 200_000
                resolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input).use { zis ->
                        var count = 0
                        while (true) {
                            val e = zis.nextEntry ?: break
                            count++
                            if (count > 2000) { sb.append("… more entries omitted\n"); break }
                            sb.append("- ").append(e.name).append(if (e.isDirectory) "/" else " (${e.size} B)").append('\n')
                            if (!e.isDirectory && budget > 0 && isTextName(e.name)) {
                                val bytes = zis.readBytes().let { if (it.size > budget) it.copyOf(budget) else it }
                                if (bytes.none { it == 0.toByte() }) {
                                    budget -= bytes.size
                                    sb.append("```\n").append(String(bytes)).append("\n```\n")
                                }
                            }
                        }
                    }
                }
                Attachment(name, mime, size, "zip", text = sb.toString())
            }
            else -> {
                if (size > MAX_TEXT) throw IllegalArgumentException("$name is larger than ${formatBytes(MAX_TEXT.toLong())}; attach a smaller file or a ZIP")
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalArgumentException("Cannot read $name")
                if (bytes.size > MAX_TEXT) throw IllegalArgumentException("$name is too large")
                if (bytes.take(4096).any { it == 0.toByte() }) throw IllegalArgumentException("$name looks binary; only text, code, images, PDF and ZIP are supported")
                Attachment(name, mime, bytes.size.toLong(), "text", text = String(bytes))
            }
        }
    }

    private fun isTextName(n: String) = n.substringAfterLast('.', "").lowercase() in setOf(
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "html", "css", "json", "xml", "yml", "yaml", "md", "txt", "gradle", "c", "h", "cpp", "dart", "sh", "sql", "properties", "toml",
    )

    private fun jpeg(bmp: Bitmap, maxSide: Int): String {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

class ChatViewModel(private val c: AppContainer, val chatId: String) : ViewModel() {
    val chat = c.db.chats().observeChat(chatId)
    val messages = c.db.chats().observeMessages(chatId)
    val providers = c.db.providers().observe()
    val projects = c.db.projects().observeActive()
    // The reply being written lives app-wide (ChatRuns), not in this screen: leaving the chat, going
    // home or swiping Gravity away does not stop it, and reopening the chat shows it.
    private val run = c.chatRuns.forChat(chatId)
    val streaming = run.streaming
    val busy = run.busy
    /** The file the message being answered asks for (PDF, ZIP, image, audio), made from the reply. */
    private var pendingMake: com.gravity.ide.core.MakeFiles.Kind? = null
    private var pendingMakeRequest = ""

    /** While live voice mode is on: the language the reply is spoken in (see LiveVoiceDialog). */
    val liveLanguage = MutableStateFlow<String?>(null)
    /** Live mode's speaking style (the AI's mood), see VoiceStyles. */
    val livePersona = MutableStateFlow<String?>(null)
    /** Live mode: answer with the cloud AI (about a second) instead of a slow phone model, when online. */
    val liveFastest = MutableStateFlow(false)

    /** Loads this chat's AI ahead of time (e.g. the phone model), so the first live reply does not wait for it. */
    /**
     * The start of what the AI reads: the system prompt (+ [extra] for this message) and the most
     * recent messages that fit. Used for replies and for the warm-up, so both start the same.
     */
    private suspend fun conversationStart(
        resolved: com.gravity.ide.ai.ResolvedModel, settings: com.gravity.ide.data.settings.AppSettings,
        project: com.gravity.ide.data.db.ProjectEntity?, extra: String, live: Boolean,
        replyTokens: Int? = null, pictureLines: Boolean = false, out: (String) -> String,
    ): Pair<String, ArrayList<LlmMessage>> {
        val projectPart = if (project == null) "" else buildString {
            val root = File(project.rootPath)
            append("\n\nThe user is working on project '${project.name}' (${project.type}). Files:\n")
            append(c.workspace.treeSummary(root, if (resolved.provider.isLocal) 120 else 300))
            File(root, "GRAVITY.md").takeIf { it.isFile }?.readText()?.take(6000)?.let { append("\nProject instructions:\n").append(it) }
        }
        // Phone models get the short prompt: they read it before their first word.
        val system = com.gravity.ide.core.ChatPrompt.forChat(
            resolved.provider.isLocal, projectPart, extra, male = com.gravity.ide.files.VoicePrefs.male(c.appContext),
        )
        // App cards and failed replies are not part of the conversation the model sees.
        val history = c.db.chats().messages(chatId).filter { it.error == null && it.content.isNotBlank() }
        // Counted in tokens (Hindi needs more), and the start moves only at fixed points, so a phone
        // model re-reads the chat rarely instead of before every reply once the chat is long.
        val (budget, step) = if (resolved.provider.isLocal) {
            val ctx = c.localHost.loadedOrNull()?.contextSize?.takeIf { it > 0 } ?: settings.localContextSize
            // Room for the reply: a normal answer, or [replyTokens] for a long one (a drawing).
            (ctx - com.gravity.ide.core.HistoryWindow.tokens(system) - (replyTokens ?: minOf(1024, ctx / 4))).coerceAtLeast(256) to ctx / 3
        } else 100_000 to 40_000
        val from = com.gravity.ide.core.HistoryWindow.start(
            history.map { com.gravity.ide.core.HistoryWindow.tokens(it.content) }, history.map { it.role == "user" }, budget, step,
        )
        // Phone models: each user message carries the language to reply in, found by the app (they
        // follow a plain instruction far better than "the user's language"). Every message gets it
        // the same way each time, so the chat the model already read still matches next time.
        // Not in live voice, which names the language itself.
        val hint = resolved.provider.isLocal && !live
        val recent = ArrayList<LlmMessage>()
        val kept = history.drop(from)
        for ((i, m) in kept.withIndex()) {
            val user = m.role == "user"
            val text = out(m.content)
            recent += LlmMessage(
                if (user) Role.USER else Role.ASSISTANT,
                when {
                    !user || !hint -> text
                    // This message asks for an online picture: its Picture lines must be in English.
                    pictureLines && i == kept.lastIndex -> com.gravity.ide.core.LanguageHint.withPictureHint(text)
                    else -> com.gravity.ide.core.LanguageHint.withHint(text)
                },
            )
        }
        return out(system) to recent
    }

    /**
     * Gets this chat's AI ready before the user sends, so the reply starts at once: a phone model is
     * loaded (this chat's own) and reads the system prompt and the chat so far now (kept in its
     * memory, so a reply only reads the new message); for a cloud AI the connection is opened now.
     */
    /**
     * The live voice instruction added to the system prompt (empty outside live mode). The warm-up
     * adds the same, so a phone model has already read it when the first spoken question comes.
     */
    private fun liveExtra(): String = liveLanguage.value?.let { lang ->
        "\n\nThis is a live voice conversation, like a phone call: the user speaks and your reply is read aloud at once. " +
            (livePersona.value ?: "Talk like a warm, friendly Indian friend.") + " Reply in $lang, in 1 to 3 short spoken " +
            "sentences; make the first sentence very short so the voice can start at once. Use simple words that sound good " +
            "when spoken. No Markdown, lists, emojis, links or code."
    } ?: ""

    fun warmUp() = viewModelScope.launch {
        val chat = c.db.chats().get(chatId) ?: return@launch
        val settings = c.settings.current()
        if ((chat.providerId ?: settings.defaultProviderId) == LocalLlamaProvider.ID) {
            // Never load a phone model just because a chat was opened unless it surely fits: a big
            // model (7B ≈ 5 GB) loaded on top of everything else can make Android close Gravity.
            val loaded = c.localHost.loadedOrNull()
            val wanted = chat.model?.let { c.db.models().get(it) }
            if (wanted == null && loaded == null) return@launch
            if (wanted != null && loaded?.modelId != wanted.id && c.localHost.memoryWarning(wanted, settings.localContextSize) != null) return@launch
        }
        val resolved = runCatching { c.providers.resolve(chat.providerId, chat.model) }.getOrNull() ?: return@launch
        if (busy.value) return@launch
        if (resolved.provider.isLocal) {
            val project = chat.projectId?.let { c.db.projects().get(it) }
            val (system, recent) = conversationStart(resolved, c.settings.current(), project, liveExtra(), liveLanguage.value != null) { it }
            if (busy.value) return@launch
            runCatching {
                // Ends with an empty user turn: some chat formats (Gemma's) put the system prompt inside
                // the first user turn, so without one the warm-up read nothing (CI: 2 tokens reused).
                val warm = listOf(LlmMessage(Role.SYSTEM, system)) + recent +
                    (if (recent.lastOrNull()?.role == Role.USER) emptyList() else listOf(LlmMessage(Role.USER, "")))
                resolved.provider.stream(LlmRequest(resolved.model, warm, maxOutputTokens = 1, warmUp = true)) { }
            }
        } else {
            val host = when (resolved.entity?.kind) {
                com.gravity.ide.ai.ProviderKind.GEMINI.name -> "https://generativelanguage.googleapis.com/"
                else -> resolved.entity?.baseUrl?.takeIf { it.startsWith("https://") }
            } ?: return@launch
            runCatching {
                withContext(Dispatchers.IO) {
                    com.gravity.ide.ai.Http.base.newCall(okhttp3.Request.Builder().url(host).head().build()).execute().close()
                }
            }
        }
    }
    val error = MutableStateFlow<String?>(null)
    /** One-off information for the user, e.g. that a retired model was replaced. */
    val notice = MutableStateFlow<String?>(null)
    val attachments = MutableStateFlow<List<Attachment>>(emptyList())

    fun updateChat(transform: (ChatEntity) -> ChatEntity) = viewModelScope.launch {
        c.db.chats().get(chatId)?.let { c.db.chats().upsert(transform(it).copy(updatedAt = System.currentTimeMillis())) }
    }

    fun attach(context: Context, uris: List<Uri>) = viewModelScope.launch {
        for (u in uris) {
            try {
                val a = withContext(Dispatchers.IO) { AttachmentReader.read(context, u) }
                attachments.value = attachments.value + a
            } catch (e: Exception) {
                error.value = e.message
            }
        }
    }

    fun removeAttachment(a: Attachment) {
        attachments.value = attachments.value - a
    }

    /** A picture or video request waiting for the user's choice: online image AI or drawing on the phone. */
    val askPictures = MutableStateFlow<String?>(null)

    fun send(text: String) {
        if (busy.value || (text.isBlank() && attachments.value.isEmpty())) return
        cutReply.value = null
        viewModelScope.launch {
            var wants = com.gravity.ide.core.MakeFiles.detect(text)
            var request = text
            // "fir se banao", "wo padh raha hoga…" right after a picture: that picture again, changed.
            if (wants == null && text.isNotBlank()) {
                val earlier = c.db.chats().messages(chatId).filter { it.role == "user" }.map { it.content }
                if (com.gravity.ide.core.MakeFiles.pictureFollowUp(text, earlier)) {
                    wants = com.gravity.ide.core.MakeFiles.Kind.IMAGE
                    request = com.gravity.ide.core.MakeFiles.followUpRequest(text, earlier)
                }
            }
            if (wants == com.gravity.ide.core.MakeFiles.Kind.IMAGE || wants == com.gravity.ide.core.MakeFiles.Kind.VIDEO) {
                // The first picture: ask where pictures come from. Also ask again if "on this phone" was
                // chosen but its picture model is not downloaded (any more).
                val maker = c.settings.current().pictureMaker
                val phoneMissing = maker == "phone" && c.phonePictures.state() != com.gravity.ide.ai.image.PhonePictures.State.Ready
                if (maker == "ask" || phoneMissing) {
                    askPictures.value = text
                    askPicturesMake = wants to request
                } else {
                    sendNow(text, wants, request)
                }
            } else {
                sendNow(text, wants, request)
            }
        }
    }

    /** What the message waiting for the pictures question asks for, and the picture request. */
    private var askPicturesMake: Pair<com.gravity.ide.core.MakeFiles.Kind?, String>? = null

    /**
     * The user's answer for pictures: "online", "phone" or "draw" (remembered; Settings → AI →
     * Pictures and videos changes it). "phone" without its picture model starts the download and
     * gives the request back to send again when it is done.
     */
    fun answerPictures(choice: String) {
        val text = askPictures.value ?: return
        askPictures.value = null
        val (make, request) = askPicturesMake?.takeIf { it.second.endsWith(text.trim()) } ?: (com.gravity.ide.core.MakeFiles.detect(text) to text)
        askPicturesMake = null
        viewModelScope.launch {
            c.settings.update { it.copy(pictureMaker = choice) }
            if (choice == "phone") {
                when (val st = c.phonePictures.state()) {
                    com.gravity.ide.ai.image.PhonePictures.State.Ready -> Unit
                    is com.gravity.ide.ai.image.PhonePictures.State.Unavailable -> {
                        c.settings.update { it.copy(pictureMaker = "ask") }
                        error.value = "Pictures cannot be made on this phone: ${st.why}. Choose the online image AI or code drawing."
                        returnedInput.value = text
                        return@launch
                    }
                    else -> {
                        runCatching { c.phonePictures.download() }.onFailure { error.value = "The picture model could not be downloaded: ${it.message}" }
                        notice.value = "Downloading the picture model (${com.gravity.ide.ai.image.PhonePictures.DOWNLOAD_LABEL}, see Phone Models). Send your request again when it is ready."
                        returnedInput.value = text
                        return@launch
                    }
                }
            }
            sendNow(text, make, request)
        }
    }

    /** Text to put back in the message box (a request that has to wait). */
    val returnedInput = MutableStateFlow<String?>(null)

    private fun sendNow(
        text: String, make: com.gravity.ide.core.MakeFiles.Kind? = com.gravity.ide.core.MakeFiles.detect(text), makeRequest: String = text,
    ) {
        if (busy.value || (text.isBlank() && attachments.value.isEmpty())) return
        val atts = attachments.value
        attachments.value = emptyList()
        busy.value = true // at once: a second tap must not start a second reply
        run.job = c.appScope.launch(Dispatchers.Main.immediate) {
          try {
            val meta = JSONArray().apply { atts.forEach { put(JSONObject().put("name", it.name).put("mime", it.mime).put("size", it.size).put("kind", it.kind)) } }
            c.db.chats().insertMessage(MessageEntity(chatId = chatId, role = "user", content = text, attachments = meta.toString(), createdAt = System.currentTimeMillis()))
            val chat = c.db.chats().get(chatId)
            if (chat != null && (chat.title == "New chat" || chat.title.isBlank()) && text.isNotBlank()) {
                c.db.chats().upsert(chat.copy(title = text.lineSequence().first().take(60), updatedAt = System.currentTimeMillis()))
            }
            pendingMake = make
            pendingMakeRequest = makeRequest
            generate(atts)
          } finally {
            busy.value = false
          }
        }
    }

    /**
     * Live voice mode: sends what the user said and calls [onReply] with the saved reply (and its
     * error, if any) when the answer is complete. Returns false if an answer is still being written.
     */
    fun sendLive(text: String, onReply: (reply: String, error: String?) -> Unit): Boolean {
        if (busy.value || text.isBlank()) return false
        busy.value = true
        run.job = c.appScope.launch(Dispatchers.Main.immediate) {
          try {
            c.db.chats().insertMessage(MessageEntity(chatId = chatId, role = "user", content = text, createdAt = System.currentTimeMillis()))
            val chat = c.db.chats().get(chatId)
            if (chat != null && (chat.title == "New chat" || chat.title.isBlank())) {
                c.db.chats().upsert(chat.copy(title = text.lineSequence().first().take(60), updatedAt = System.currentTimeMillis()))
            }
            try {
                generate(emptyList())
            } catch (e: CancellationException) {
                onReply("", "Stopped")
                throw e
            }
            val last = c.db.chats().messages(chatId).lastOrNull()
            if (last != null && last.role == "assistant") onReply(last.content, last.error) else onReply("", "No reply was saved")
          } finally {
            busy.value = false
          }
        }
        return true
    }

    fun regenerate() {
        if (busy.value) return
        busy.value = true
        run.job = c.appScope.launch(Dispatchers.Main.immediate) {
          try {
            val msgs = c.db.chats().messages(chatId)
            val last = msgs.lastOrNull()
            if (last != null && last.role == "assistant") c.db.chats().deleteMessage(last.id)
            // A regenerated answer to "make a PDF/ZIP/image/audio" makes the file again.
            msgs.lastOrNull { it.role == "user" }?.content?.let { req ->
                pendingMake = com.gravity.ide.core.MakeFiles.detect(req)
                pendingMakeRequest = req
            }
            generate(emptyList())
          } finally {
            busy.value = false
          }
        }
    }

    // One word, so no reply language is added to it: the rest keeps the language of the reply.
    fun continueGeneration() = send("continue")

    /** The last reply, when it stopped at the length limit (it shows a Continue button). */
    val cutReply = MutableStateFlow<Long?>(null)

    /**
     * Room for a list the user asked for by count (or for more of it: "continue"), for a phone
     * model: about 10 tokens an item, 1024 to 4096. Null: the usual length.
     */
    private fun listTokens(recent: List<LlmMessage>): Int? {
        val asks = recent.filter { it.role == Role.USER }.map { it.text.substringBefore("\n\n(") }
        val last = asks.lastOrNull() ?: return null
        val n = com.gravity.ide.core.LanguageHint.listCount(last)
            ?: if (com.gravity.ide.core.LanguageHint.isFollowUp(last)) asks.dropLast(1).lastOrNull()?.let { com.gravity.ide.core.LanguageHint.listCount(it) } else null
        return n?.let { (it * 10 + 150).coerceIn(1024, 4096) }
    }

    fun stop() {
        run.job?.cancel()
    }

    private suspend fun generate(atts: List<Attachment>) {
        val chat = c.db.chats().get(chatId) ?: return
        busy.value = true
        c.chatRuns.active(chatId, true)
        error.value = null
        streaming.value = ""
        var resolvedModel: String? = null
        var providerId: String? = null
        val text = StringBuilder()
        val sources = mutableListOf<WebSource>()
        var inTok = 0
        var speed: LlmEvent.Speed? = null
        var finish: String? = null
        var outTok = 0
        try {
            suspend fun answerWith(resolved: com.gravity.ide.ai.ResolvedModel) {
                resolvedModel = resolved.model
                providerId = resolved.entity?.id ?: if (resolved.provider.isLocal) LocalLlamaProvider.ID else null
                val project = chat.projectId?.let { c.db.projects().get(it) }
                if (project != null && project.localOnly && !resolved.provider.isLocal) {
                    throw IllegalStateException("${project.name} is a local-only project: its context is never sent to a cloud model. Select the local model for this chat.")
                }
                val settings = c.settings.current()
                val redact = settings.redactSecrets && !resolved.provider.isLocal
                fun out(s: String): String {
                    if (!redact) return s
                    val (r, f) = SecretScanner.redact(s)
                    if (f.isNotEmpty()) c.events.log(EventCategory.SECURITY, "Redacted ${f.size} secret(s) from chat content sent to ${resolved.model}", level = EventLevel.WARN)
                    return r
                }
                val llm = mutableListOf<LlmMessage>()
                val extra = buildString {
                    // Online and on-phone image AIs both make the picture from the chat AI's description.
                    pendingMake?.let { append("\n\n").append(com.gravity.ide.core.MakeFiles.instruction(it, onlinePictures = settings.pictureMaker == "online" || settings.pictureMaker == "phone")) }
                    append(liveExtra())
                }
                // A picture or video is drawn as SVG code: a phone model needs room for a long reply.
                // A picture fits the context the model already has (old messages make room); a bigger
                // context costs a lot of memory mid-chat, and phones closed Gravity for it. A video
                // (several drawings) gets a bigger one only if memory clearly allows.
                val live = liveLanguage.value != null
                val drawing = !live && resolved.provider.isLocal && settings.pictureMaker == "draw" &&
                    (pendingMake == com.gravity.ide.core.MakeFiles.Kind.IMAGE || pendingMake == com.gravity.ide.core.MakeFiles.Kind.VIDEO)
                val loadedCtx = c.localHost.loadedOrNull()?.contextSize?.takeIf { it > 0 } ?: settings.localContextSize
                val drawContext = if (drawing && pendingMake == com.gravity.ide.core.MakeFiles.Kind.VIDEO) {
                    c.localHost.loadedOrNull()?.let { c.localHost.contextForLongAnswer(it, 4096 + 4000) }?.takeIf { it > loadedCtx }
                } else null
                val drawTokens = pendingMake?.takeIf { drawing }?.let { com.gravity.ide.core.MakeFiles.drawTokens(it, drawContext ?: loadedCtx) }
                // The same start as the warm-up built (see warmUp), so a phone model reuses what it already read.
                val (system, recent) = conversationStart(
                    resolved, settings, project, extra, live, drawTokens,
                    pictureLines = (settings.pictureMaker == "online" || settings.pictureMaker == "phone") &&
                        (pendingMake == com.gravity.ide.core.MakeFiles.Kind.IMAGE || pendingMake == com.gravity.ide.core.MakeFiles.Kind.VIDEO),
                    out = ::out,
                )
                llm += LlmMessage(Role.SYSTEM, system)
                var contextSize: Int? = null
                if (atts.isNotEmpty() && recent.isNotEmpty() && recent.last().role == Role.USER) {
                    val last = recent.removeAt(recent.size - 1)
                    var attachedText = atts.mapNotNull { a -> a.text?.let { "Attachment ${a.name}:\n```\n${out(it)}\n```" } }.joinToString("\n\n")
                    if (resolved.provider.isLocal && attachedText.isNotEmpty()) {
                        // A phone model reads the file as text: ask for a context big enough for it (if free
                        // memory allows) and leave out what still does not fit, saying so.
                        val otherChars = system.length + recent.sumOf { it.text.length } + last.text.length
                        val wanted = (((otherChars + attachedText.length) / 3 + 1500 + 1023) / 1024 * 1024).coerceIn(4096, 32768)
                        val ctx = c.localHost.loadedOrNull()?.let { c.localHost.contextForLongAnswer(it, wanted) } ?: settings.localContextSize
                        contextSize = ctx
                        val room = (ctx - 1200) * 3 - otherChars
                        if (attachedText.length > room) {
                            attachedText = attachedText.take(maxOf(0, room - 200)) +
                                "\n[… the rest of the file did not fit in the phone model's memory ($ctx tokens). Ask about a part of it, or use a cloud AI for the whole file.]"
                        }
                    }
                    recent += last.copy(text = listOf(last.text, attachedText).filter { it.isNotBlank() }.joinToString("\n\n"), images = atts.flatMap { it.images })
                }
                llm += recent
                val mode = ReasoningMode.fromName(chat.reasoning).let { if (it == ReasoningMode.AUTO) ReasoningMapper.resolveAuto(recent.lastOrNull()?.text ?: "") else it }
                // If Gravity closes during this reply, the next start says what it was doing.
                val make = pendingMake
                val workId = c.crashReports.begin(
                    "writing a reply with ${resolved.model}" +
                        (if (resolved.provider.isLocal) " on the phone, context ${contextSize ?: drawContext ?: loadedCtx}" else "") +
                        when {
                            make != null && drawTokens != null -> ", drawing the ${make.label} (up to $drawTokens tokens)"
                            make != null -> " for the ${make.label}"
                            else -> ""
                        },
                )
                // Live voice: short spoken answers come back much sooner (and are spoken while written).
                try { resolved.provider.stream(
                    LlmRequest(
                        resolved.model, llm, reasoning = if (live) ReasoningMode.FAST else mode, webGrounding = chat.webGrounding,
                        contextSize = contextSize ?: drawContext,
                        maxOutputTokens = when {
                            live -> if (resolved.provider.isLocal) 160 else 260
                            drawTokens != null -> drawTokens
                            // A list asked for by count ("500 names") gets room for it on the phone.
                            resolved.provider.isLocal -> listTokens(recent)
                            else -> null
                        },
                    )
                ) { ev ->
                    when (ev) {
                        is LlmEvent.TextDelta -> {
                            text.append(ev.text)
                            streaming.value = text.toString()
                        }
                        is LlmEvent.Usage -> {
                            inTok = maxOf(inTok, ev.inputTokens)
                            outTok = maxOf(outTok, ev.outputTokens)
                        }
                        is LlmEvent.Sources -> sources += ev.sources
                        is LlmEvent.Retract -> {
                            text.setLength(maxOf(0, text.length - ev.chars))
                            streaming.value = text.toString()
                        }
                        is LlmEvent.Speed -> speed = ev
                        is LlmEvent.Done -> finish = ev.finishReason
                        is LlmEvent.ModelSwitched -> {
                            resolvedModel = ev.to
                            notice.value = when {
                                ev.reason.isNotBlank() -> "${ev.reason}: answering with ${ev.to}."
                                ev.from.isBlank() -> "Using ${ev.to}, the newest model your key can use."
                                else -> "${ev.from} is no longer offered by the provider. Switched to ${ev.to} (newest stable model for your key)."
                            }
                        }
                        else -> Unit
                    }
                } } finally { c.crashReports.end(workId) }
                c.providers.recordUsage(resolved.entity, inTok, outTok)
            }
            try {
                // Live mode "fastest": the first enabled cloud AI, when online; otherwise this chat's AI.
                // (A chat that already uses a cloud AI keeps its own AI and model.)
                val fastCloud = if (liveLanguage.value != null && liveFastest.value && c.network.isOnline) {
                    val cloud = c.db.providers().all().filter { it.enabled && it.kind != com.gravity.ide.ai.ProviderKind.LOCAL.name }
                    if (cloud.any { it.id == chat.providerId }) null else cloud.firstOrNull()
                } else null
                answerWith(
                    if (fastCloud != null) runCatching { c.providers.resolve(fastCloud.id, null) }.getOrElse { c.providers.resolve(chat.providerId, chat.model) }
                    else c.providers.resolve(chat.providerId, chat.model)
                )
            } catch (e: Exception) {
                // The cloud key's free quota is used up (all its models): answer with the phone model if there is one.
                if (text.isNotEmpty() || !com.gravity.ide.core.ModelPicker.isTemporarilyUnavailable(e)) throw e
                val phone = runCatching { c.providers.resolve(LocalLlamaProvider.ID, null) }.getOrNull()?.takeIf { it.provider.isLocal } ?: throw e
                notice.value = (e.message ?: "The cloud AI's free limit is used up") + " This answer is from the phone model (${phone.model})."
                try {
                    answerWith(phone)
                } catch (pe: CancellationException) {
                    throw pe
                } catch (pe: Exception) {
                    if (text.isNotEmpty()) throw pe
                    notice.value = null
                    // Say both reasons: why the cloud AI did not answer, and why the phone model did not.
                    throw IllegalStateException("${e.message}\n\nThe phone model could not answer either: ${pe.message}", pe)
                }
            }
            // A chat AI that says it made a picture when none was asked for (none is being made):
            // say so in the reply, so a pretend picture is not taken for a real one.
            if (pendingMake == null && com.gravity.ide.core.MakeFiles.claimsPicture(text.toString(), phoneModel = providerId == LocalLlamaProvider.ID)) {
                text.append("\n\n").append(com.gravity.ide.core.MakeFiles.NO_PICTURE_NOTE)
            }
            val savedId = saveAssistant(text.toString(), resolvedModel, providerId, inTok, outTok, sources, null)
            // Stopped at the length limit: the reply gets a Continue button.
            cutReply.value = if (finish == "MAX_TOKENS" || finish == "length") savedId else null
            speed?.let { v -> c.replySpeeds.put(savedId, com.gravity.ide.core.ReplySpeed.line(v.promptTokens, v.reusedTokens, v.readMs, v.outputTokens, v.writeMs, v.threads, v.engine)) }
            c.chatRuns.notifyReady(chatId, c.db.chats().get(chatId)?.title.orEmpty(), text.toString())
            pendingMake?.let { kind -> if (text.isNotBlank()) makeFile(savedId, kind, pendingMakeRequest) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { saveAssistant(text.toString() + "\n\n_(generation stopped)_", resolvedModel, providerId, inTok, outTok, sources, null) }
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            // Shown in the reply itself (with Try again / Choose model), so no extra banner.
            saveAssistant(text.toString(), resolvedModel, providerId, inTok, outTok, sources, msg)
        } finally {
            busy.value = false
            streaming.value = null
            pendingMake = null
            c.chatRuns.active(chatId, false)
        }
    }

    /**
     * A picture description for the image AI, in English: it misreads other scripts (a Hindi
     * "elephant" description gave a picture of a girl). If the AI still wrote it in Hindi, the
     * chat's AI translates just that line; if that fails, the description is used as it is.
     */
    private suspend fun inEnglish(desc: String): String {
        if (!com.gravity.ide.core.LanguageHint.mostlyNonLatin(desc)) return desc
        val chat = c.db.chats().get(chatId) ?: return desc
        return runCatching {
            val resolved = c.providers.resolve(chat.providerId, chat.model)
            val out = StringBuilder()
            resolved.provider.stream(
                LlmRequest(
                    resolved.model,
                    listOf(
                        LlmMessage(Role.SYSTEM, "Translate the user's picture description into English for an image AI. Reply with the English description only, on one line."),
                        LlmMessage(Role.USER, desc),
                    ),
                    maxOutputTokens = 250, temperature = 0.2,
                )
            ) { ev -> if (ev is LlmEvent.TextDelta) out.append(ev.text) }
            out.toString().lines().map { it.trim().removePrefix("Picture:").trim() }.firstOrNull { it.isNotEmpty() }
                ?.takeIf { it.length > 10 && !com.gravity.ide.core.LanguageHint.mostlyNonLatin(it) } ?: desc
        }.getOrDefault(desc)
    }

    /**
     * Makes a file from an AI reply, on the phone (no cloud, no limits): PDF of the text, ZIP of its
     * code blocks (or of this chat's app), PNG of the SVG it drew, or WAV of it read aloud. The file
     * is added to the reply as a card with Open and Share.
     */
    fun makeFile(messageId: Long, kind: com.gravity.ide.core.MakeFiles.Kind, request: String = "") = c.appScope.launch(Dispatchers.Main.immediate) {
        val msg = c.db.chats().messages(chatId).firstOrNull { it.id == messageId } ?: return@launch
        val ctx = c.appContext
        notice.value = "Making the ${kind.label}…"
        var videoNote: String? = null
        val maker = c.settings.current().pictureMaker
        val online = maker == "online"
        // Realistic pictures made on this phone (Stable Diffusion), from the chat AI's description.
        val onPhone = maker == "phone"
        val workId = c.crashReports.begin(
            "making the ${kind.label}" + (
                if (kind == com.gravity.ide.core.MakeFiles.Kind.IMAGE || kind == com.gravity.ide.core.MakeFiles.Kind.VIDEO) {
                    when { onPhone -> " with the phone's picture model"; online -> " with the online image AI"; else -> " from the AI's drawing" }
                } else ""
                ),
        )
        try {
            val name = com.gravity.ide.core.MakeFiles.baseName(request.ifBlank { msg.content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }, kind.name.lowercase())
            val file = withContext(Dispatchers.IO) {
                when (kind) {
                    com.gravity.ide.core.MakeFiles.Kind.PDF -> com.gravity.ide.files.FileMaker.pdf(ctx, name, msg.content)
                    com.gravity.ide.core.MakeFiles.Kind.ZIP -> {
                        val files = com.gravity.ide.core.MakeFiles.codeFiles(msg.content)
                        val project = c.db.chats().get(chatId)?.projectId?.let { c.db.projects().get(it) }
                        when {
                            files.isNotEmpty() -> com.gravity.ide.files.FileMaker.zip(ctx, name, files.map { it.first to it.second.toByteArray() })
                            project != null -> com.gravity.ide.files.FileMaker.zipFolder(ctx, project.name, File(project.rootPath))
                            else -> throw IllegalStateException("the reply has no files (code blocks) to put in a ZIP")
                        }
                    }
                    com.gravity.ide.core.MakeFiles.Kind.IMAGE -> {
                        val described = com.gravity.ide.core.MakeFiles.picturePrompt(msg.content)
                        val svg = com.gravity.ide.core.MakeFiles.svg(msg.content)
                        if (onPhone && (described != null || svg == null)) {
                            // A realistic picture made on this phone, without internet.
                            val desc = inEnglish(described ?: request.ifBlank { msg.content.take(300) })
                            val size = c.settings.current().phonePictureSize
                            val started = System.currentTimeMillis()
                            notice.value = "Making the picture on this phone (about ${if (size <= 384) "1–2" else "2–3"} minutes)…"
                            val bmp = c.phonePictures.make(desc, size, size) { step, steps ->
                                notice.value = "Making the picture on this phone: step $step of $steps, ${(System.currentTimeMillis() - started) / 1000} s…"
                            }
                            val f = com.gravity.ide.files.FileMaker.unique(com.gravity.ide.files.FileMaker.dir(ctx), com.gravity.ide.files.FileMaker.clean(name) + ".jpg")
                            try {
                                java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                            } finally {
                                bmp.recycle()
                            }
                            videoNote = "Made on this phone by Stable Diffusion (Realistic Vision 5.1, ${size} px, ${(System.currentTimeMillis() - started) / 1000} s) from: \"${desc.take(160)}\""
                            f
                        } else if (described != null || (online && svg == null)) {
                            // A real AI picture from the online image AI (the user chose it).
                            val desc = inEnglish(described ?: request.ifBlank { msg.content.take(300) })
                            notice.value = "Making the picture with the free online image AI…"
                            val bytes = com.gravity.ide.files.OnlineImages.generate(desc, 1024, 1024)
                            val f = com.gravity.ide.files.FileMaker.unique(com.gravity.ide.files.FileMaker.dir(ctx), com.gravity.ide.files.FileMaker.clean(name) + ".jpg")
                            f.writeBytes(bytes)
                            videoNote = "Made by the free online image AI (${com.gravity.ide.files.OnlineImages.SERVICE}) from: \"${desc.take(160)}\""
                            f
                        } else {
                            com.gravity.ide.files.FileMaker.png(
                                ctx, name,
                                svg ?: throw IllegalStateException(
                                    "the AI did not draw a picture (no SVG in its reply). Try again, or turn on the free online image AI in Settings → AI → Pictures and videos"
                                ),
                            ).also { videoNote = "Drawn by the chat's AI (code drawing). For real AI pictures, choose the free online image AI in Settings → AI → Pictures and videos." }
                        }
                    }
                    com.gravity.ide.core.MakeFiles.Kind.VIDEO -> {
                        val described = com.gravity.ide.core.MakeFiles.pictureScenes(msg.content)
                        val drawn = com.gravity.ide.core.MakeFiles.scenes(msg.content)
                        val narrations = if (described.isNotEmpty()) described.map { it.first } else drawn.map { it.first }
                        val text = narrations.joinToString(" ")
                        val hindi = com.gravity.ide.files.VoicePrefs.isHindi(ctx, text)
                        val locale = java.util.Locale.forLanguageTag(if (hindi) "hi-IN" else "en-IN")
                        val made = if (described.isNotEmpty()) {
                            // Each scene's picture from the online image AI (one per 45 s on the free service),
                            // or made on this phone (portrait 384x640, filled to the video's size).
                            val pictures = ArrayList<android.graphics.Bitmap>()
                            try {
                                described.forEachIndexed { i, (_, desc) ->
                                    pictures += if (onPhone) {
                                        notice.value = "Making picture ${i + 1} of ${described.size} on this phone (about 1–2 minutes each)…"
                                        val raw = c.phonePictures.make("${inEnglish(desc)}, vertical portrait composition", 384, 640) { step, steps ->
                                            notice.value = "Picture ${i + 1} of ${described.size} on this phone: step $step of $steps…"
                                        }
                                        com.gravity.ide.files.OnlineImages.cover(raw, com.gravity.ide.files.VideoMaker.W, com.gravity.ide.files.VideoMaker.H)
                                    } else {
                                        notice.value = "Making picture ${i + 1} of ${described.size} with the free online image AI (one per 45 s on the free service)…"
                                        com.gravity.ide.files.OnlineImages.bitmap("${inEnglish(desc)}, vertical portrait composition", com.gravity.ide.files.VideoMaker.W, com.gravity.ide.files.VideoMaker.H)
                                    }
                                }
                            } catch (e: Exception) {
                                pictures.forEach { it.recycle() }
                                throw e
                            }
                            com.gravity.ide.files.VideoMaker.makeFromPictures(ctx, name, narrations, pictures, locale) { step -> notice.value = step }
                        } else {
                            if (drawn.isEmpty()) throw IllegalStateException("the AI wrote no scenes with pictures. Try again (Gemini follows the format best)")
                            com.gravity.ide.files.VideoMaker.make(ctx, name, drawn, locale) { step -> notice.value = step }
                        }
                        videoNote = "Made a ${"%.0f".format(made.seconds)}-second video on this phone: ${made.scenes} scene(s), " +
                            (if (described.isEmpty()) "pictures drawn by the chat's AI" else if (onPhone) "realistic pictures made on this phone" else "pictures by the free online image AI") +
                            (if (made.withVoice) ", narration read by the phone's voice." else ", without voice (this phone's text-to-speech did not work).") +
                            " It is picture by picture, not AI-moving video (that needs a paid video AI)."
                        made.file
                    }
                    com.gravity.ide.core.MakeFiles.Kind.AUDIO -> {
                        val devanagari = msg.content.count { it in '\u0900'..'\u097F' }
                        val letters = msg.content.count { it.isLetter() }.coerceAtLeast(1)
                        val locale = java.util.Locale.forLanguageTag(if (devanagari * 10 > letters * 3 || com.gravity.ide.files.VoicePrefs.isHindi(ctx, msg.content)) "hi-IN" else "en-IN")
                        com.gravity.ide.files.FileMaker.audio(ctx, name, msg.content, locale)
                    }
                }
            }
            val mime = when (file.extension.lowercase()) {
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "wav" -> "audio/wav"
                "mp4" -> "video/mp4"
                else -> "application/octet-stream"
            }
            val atts = runCatching { JSONArray(msg.attachments) }.getOrElse { JSONArray() }
            atts.put(JSONObject().put("kind", "file").put("name", file.name).put("path", file.absolutePath).put("mime", mime).put("size", file.length()))
            val latest = c.db.chats().messages(chatId).firstOrNull { it.id == messageId } ?: msg
            c.db.chats().updateMessage(latest.copy(attachments = atts.toString()))
            c.events.log(EventCategory.TOOL, "Made ${file.name}", "${file.length()} bytes from a chat reply")
            notice.value = videoNote
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice.value = null
            error.value = "Could not make the ${kind.label}: ${e.message}"
        } catch (e: OutOfMemoryError) {
            // A drawing too big or complex for the phone's memory: say so instead of closing.
            notice.value = null
            error.value = "Could not make the ${kind.label}: the phone ran out of memory for it. Close other apps and try again, or ask for a simpler one."
        } catch (e: StackOverflowError) {
            // An AI drawing that refers to itself (nested without end).
            notice.value = null
            error.value = "Could not make the ${kind.label}: the AI's drawing is broken (it refers to itself). Ask again for the picture."
        } finally {
            c.crashReports.end(workId)
        }
    }

    private suspend fun saveAssistant(text: String, model: String?, providerId: String?, inTok: Int, outTok: Int, sources: List<WebSource>, error: String?): Long {
        val src = JSONArray().apply { sources.distinctBy { it.url }.forEach { put(JSONObject().put("title", it.title).put("url", it.url)) } }
        val id = c.db.chats().insertMessage(
            MessageEntity(
                chatId = chatId, role = "assistant", content = text, model = model, providerId = providerId,
                inputTokens = inTok, outputTokens = outTok, sources = src.toString(), error = error, createdAt = System.currentTimeMillis(),
            )
        )
        c.db.chats().get(chatId)?.let { c.db.chats().upsert(it.copy(updatedAt = System.currentTimeMillis())) }
        return id
    }

    fun deleteMessage(id: Long) = viewModelScope.launch { c.db.chats().deleteMessage(id) }

    /** App mode: messages build an app, or change the app this chat is about. */
    val appMode = MutableStateFlow(false)
    private var modeChosen = false

    fun chooseMode(app: Boolean) {
        modeChosen = true
        appMode.value = app
    }

    /** Switches to App mode when the text clearly asks for an app, unless the user picked a mode. */
    fun suggestMode(text: String) {
        if (!modeChosen && !appMode.value && com.gravity.ide.agent.AppBuilder.looksLikeAppRequest(text)) appMode.value = true
    }

    init {
        viewModelScope.launch {
            val chat = c.db.chats().get(chatId)
            val linked = chat?.projectId?.let { c.db.projects().get(it) }
            if (linked?.type == "APP") appMode.value = true
        }
    }

    fun sendApp(text: String) {
        val atts = attachments.value
        if (busy.value || (text.isBlank() && atts.isEmpty())) return
        // A picture is a design to copy; a text, code or PDF file gives details. Other files (ZIP, audio…) can't be used.
        val unusable = atts.firstOrNull { it.kind != "image" && it.text.isNullOrBlank() && it.images.isEmpty() }
        if (unusable != null) {
            error.value = "${unusable.name} can't be used to build an app. Attach a design picture (screenshot, mockup) or a text / PDF file with details."
            return
        }
        val designs = atts.filter { it.kind == "image" || it.text.isNullOrBlank() }.flatMap { it.images }
        val references = atts.filter { it.kind != "image" && !it.text.isNullOrBlank() }.map { it.name to it.text!! }
        attachments.value = emptyList()
        viewModelScope.launch {
            busy.value = true
            try {
                val now = System.currentTimeMillis()
                val meta = JSONArray().apply { atts.forEach { put(JSONObject().put("name", it.name).put("mime", it.mime).put("size", it.size).put("kind", it.kind)) } }
                c.db.chats().insertMessage(
                    MessageEntity(chatId = chatId, role = "user", content = text.ifBlank { "Build this design" }, attachments = meta.toString(), createdAt = now)
                )
                val chat = c.db.chats().get(chatId)
                val linked = chat?.projectId?.let { c.db.projects().get(it) }?.takeIf { it.type == "APP" }
                // The app is built by the AI chosen in this chat (e.g. the phone model), not always the default.
                val providerId = chat?.providerId
                val model = chat?.model
                val (project, taskId) = if (linked != null) linked to c.appBuilder.change(linked, text, providerId, model, designs, references)
                else c.appBuilder.create(text, providerId, model, designs, references)
                if (chat != null && linked == null) {
                    c.db.chats().upsert(
                        chat.copy(projectId = project.id, title = if (chat.title == "New chat" || chat.title.isBlank()) project.name else chat.title, updatedAt = now)
                    )
                }
                addAppCard(project.id, taskId, isChange = linked != null)
            } catch (e: Exception) {
                error.value = e.message ?: "Could not start the app"
            } finally {
                busy.value = false
            }
        }
    }

    fun retryApp(projectId: String, taskId: String) = viewModelScope.launch {
        try {
            val project = c.db.projects().get(projectId) ?: return@launch
            val task = c.db.tasks().get(taskId) ?: return@launch
            val newId = c.appBuilder.retry(project, task)
            addAppCard(projectId, newId, isChange = com.gravity.ide.agent.AppBuilder.isChangeInstruction(task.instruction))
        } catch (e: Exception) {
            error.value = e.message ?: "Could not try again"
        }
    }

    private suspend fun addAppCard(projectId: String, taskId: String, isChange: Boolean) {
        val card = JSONArray().put(JSONObject().put("kind", "app").put("projectId", projectId).put("taskId", taskId).put("change", isChange))
        c.db.chats().insertMessage(MessageEntity(chatId = chatId, role = "assistant", content = "", attachments = card.toString(), createdAt = System.currentTimeMillis()))
        c.db.chats().get(chatId)?.let { c.db.chats().upsert(it.copy(updatedAt = System.currentTimeMillis())) }
    }
}

/** A chat for a new conversation: the newest empty one is reused, so empty chats don't pile up. */
suspend fun freshChatId(c: AppContainer): String {
    val empty = c.db.chats().all().filter { it.title == "New chat" && !it.archived }.sortedByDescending { it.updatedAt }
        .firstOrNull { c.db.chats().messages(it.id).isEmpty() }
    if (empty != null) return empty.id
    val s = c.settings.current()
    val id = UUID.randomUUID().toString()
    val now = System.currentTimeMillis()
    c.db.chats().upsert(ChatEntity(id = id, title = "New chat", providerId = s.defaultProviderId, model = s.defaultModel, reasoning = s.reasoning.name, createdAt = now, updatedAt = now))
    return id
}

/** Home: a fresh conversation, like opening Claude. */
@Composable
fun HomeChatScreen(go: (String) -> Unit) {
    val c = LocalAppContainer.current
    var chatId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (chatId == null) chatId = freshChatId(c) }
    val id = chatId
    if (id == null) Box(Modifier.fillMaxSize()) else ChatScreen(id, go)
}

@Composable
fun NewChatRedirect(onCreated: (String) -> Unit) {
    val c = LocalAppContainer.current
    LaunchedEffect(Unit) { onCreated(freshChatId(c)) }
    Box(Modifier.fillMaxSize())
}

@Composable
fun ChatScreen(chatId: String, go: (String) -> Unit) {
    val vm = gravityViewModel(key = "chat-$chatId") { ChatViewModel(it, chatId) }
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // Ticks when a phone-model reply's speed line is saved, so the bubbles read it again.
    val speedTick by c.replySpeeds.changes.collectAsState()
    val cutId by vm.cutReply.collectAsState()
    val chat by vm.chat.collectAsState(initial = null)
    val messages by vm.messages.collectAsState(initial = emptyList())
    val streaming by vm.streaming.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val notice by vm.notice.collectAsState()
    val attachments by vm.attachments.collectAsState()
    val providers by vm.providers.collectAsState(initial = emptyList())
    val projects by vm.projects.collectAsState(initial = emptyList())
    val local by c.localHost.state.collectAsState()
    var input by remember { mutableStateOf("") }
    var modelSheet by remember { mutableStateOf(false) }
    var aiSetup by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        com.gravity.ide.files.ReplySpeaker.problem.collect { p -> if (p != null) { toast(context, p); com.gravity.ide.files.ReplySpeaker.problem.value = null } }
    }
    DisposableEffect(Unit) { onDispose { com.gravity.ide.files.ReplySpeaker.stop() } }

    LaunchedEffect(messages.size, streaming?.length) {
        val count = messages.size + if (streaming != null) 1 else 0
        if (count > 0) {
            listState.scrollToItem(count - 1)
            listState.scrollBy(100_000f) // to the bottom of a long last message
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.attach(context, uris) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!said.isNullOrBlank()) input = if (input.isBlank()) said else "$input $said"
        }
    }

    val ch = chat ?: run {
        Box(Modifier.fillMaxSize())
        return
    }
    val appMode by vm.appMode.collectAsState()
    // Get this chat's AI ready now (load and pre-read for phone models, open the connection for cloud AI).
    LaunchedEffect(ch.id, ch.providerId, ch.model) { vm.warmUp() }
    val localModels by c.db.models().observe().collectAsState(initial = emptyList())
    val loadedLocal = (local as? com.gravity.ide.ai.local.LocalModelHost.State.Ready)?.loaded
    val label = modelLabelFor(ch, providers, loadedLocal, localModels)
    val hasCloud = providers.any { it.enabled && it.kind != com.gravity.ide.ai.ProviderKind.LOCAL.name }
    val hasPhoneModel = c.localHost.runtimeAvailable && localModels.any { it.status == com.gravity.ide.models.ModelStatus.READY.name }
    fun share(text: String) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"))
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (messages.isEmpty() && streaming == null) item {
                ChatWelcome(
                    needsSetup = !hasCloud && !hasPhoneModel,
                    onSetup = { aiSetup = true },
                    onSuggestion = { text, app -> vm.chooseMode(app); input = text },
                    appMode = appMode,
                    onModeChange = { vm.chooseMode(it) },
                )
            }
            val lastId = messages.lastOrNull()?.id
            items(messages, key = { it.id }) { m ->
                ChatBubble(
                    m, isLast = m.id == lastId, busy = busy,
                    onCopy = { clipboard.setText(AnnotatedString(m.content)); toast(context, "Copied") },
                    onRetry = { vm.regenerate() },
                    onShare = { share(m.content) },
                    onDelete = { vm.deleteMessage(m.id) },
                    onChangeModel = { modelSheet = true },
                    onOpenUrl = { go(Routes.browser(it)) },
                    onOpenApp = { go(Routes.app(it)) },
                    onRetryApp = { p, t -> vm.retryApp(p, t) },
                    onMake = { kind -> vm.makeFile(m.id, kind) },
                    onContinue = if (m.id == cutId && m.id == lastId) ({ vm.continueGeneration() }) else null,
                    speed = if (m.role == "assistant" && m.providerId == LocalLlamaProvider.ID) remember(m.id, speedTick) { c.replySpeeds.get(m.id) } else null,
                )
            }
            streaming?.let { s -> item { StreamingBubble(s) } }
        }
        error?.let { MessageBanner(it, isError = true) { vm.error.value = null } }
        notice?.let { MessageBanner(it) { vm.notice.value = null } }
        ChatComposer(
            value = input,
            // Only a new chat picks its mode from the text; a started chat keeps Chat or App.
            onValueChange = { input = it; if (messages.isEmpty()) vm.suggestMode(it) },
            busy = busy, attachments = attachments,
            onRemoveAttachment = { vm.removeAttachment(it) },
            onAttachFiles = { picker.launch(arrayOf("*/*")) },
            onAttachImages = { picker.launch(arrayOf("image/*")) },
            onMic = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message")
                try {
                    speech.launch(intent)
                } catch (e: Exception) {
                    toast(context, "No speech recognizer is available on this device")
                }
            },
            onSend = {
                val text = input
                if (appMode) {
                    if (!hasCloud && !hasPhoneModel) aiSetup = true
                    else if (text.isNotBlank() || attachments.isNotEmpty()) { vm.sendApp(text); input = "" }
                } else {
                    vm.send(text)
                    input = ""
                }
            },
            onStop = { vm.stop() },
            appMode = appMode,
            onModeChange = { vm.chooseMode(it) },
            showModeSwitch = false,
            started = messages.isNotEmpty(),
            modelLabel = label,
            onPickModel = { modelSheet = true },
            onLive = { if (!hasCloud && !hasPhoneModel) aiSetup = true else live = true },
        )
    }
    if (live) LiveVoiceDialog(vm, label.text, onClose = { live = false })

    if (modelSheet) {
        ModelPickerSheet(
            ch, providers, projects.map { it.id to it.name },
            onDismiss = { modelSheet = false },
            onUpdate = { updated -> vm.updateChat { updated } },
            onAddAi = { modelSheet = false; aiSetup = true },
            go = go,
        )
    }
    if (aiSetup) AiSetupDialog(onDismiss = { aiSetup = false }, go = go)
    // The first picture or video: where the pictures come from (asked once, changeable in Settings).
    val askPictures by vm.askPictures.collectAsState()
    askPictures?.let { pending ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.askPictures.value = null; input = pending },
            title = { Text("Where should pictures come from?") },
            text = {
                Text(
                    "Online: Pollinations.ai, a free online image AI (no account). Only the picture's description is sent there; one picture " +
                        "at a time, at least 45 seconds apart, and it may add a small watermark.\n\n" +
                        "On this phone: realistic pictures by Stable Diffusion, without internet. Needs its picture model once " +
                        "(${com.gravity.ide.ai.image.PhonePictures.DOWNLOAD_LABEL}); about 1–3 minutes a picture.\n\n" +
                        "Code drawing: the chat's AI draws simple pictures as code.\n\n" +
                        "You can change this any time in Settings → AI → Pictures and videos."
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { vm.answerPictures("online") }, modifier = Modifier.testTag("pictures-online")) { Text("Online image AI") }
                    TextButton(onClick = { vm.answerPictures("phone") }, modifier = Modifier.testTag("pictures-phone")) { Text("On this phone (realistic)") }
                    TextButton(onClick = { vm.answerPictures("draw") }, modifier = Modifier.testTag("pictures-draw")) { Text("Code drawing (simple)") }
                }
            },
        )
    }
    // A request that has to wait (the picture model is downloading) goes back into the message box.
    val returned by vm.returnedInput.collectAsState()
    LaunchedEffect(returned) {
        returned?.let { input = it; vm.returnedInput.value = null }
    }
}

@Composable
fun ChatListScreen(go: (String) -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var archived by remember { mutableStateOf(false) }
    val chats by c.db.chats().observe(archived).collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var messageHits by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    var renaming by remember { mutableStateOf<ChatEntity?>(null) }
    var deleting by remember { mutableStateOf<ChatEntity?>(null) }

    LaunchedEffect(query) { messageHits = if (query.length >= 2) c.db.chats().searchMessages(query) else emptyList() }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search chats and messages") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !archived, onClick = { archived = false }, label = { Text("Chats") })
                FilterChip(selected = archived, onClick = { archived = true }, label = { Text("Archived") })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { go(Routes.NEW_CHAT) }) { Text("New chat") }
            }
        }
        val filtered = chats.filter { query.isBlank() || it.title.contains(query, true) }
        if (filtered.isEmpty() && messageHits.isEmpty()) item { EmptyState("No chats", "Start a new chat from the dashboard or the drawer.") }
        items(filtered, key = { it.id }) { ch ->
            Panel(onClick = { go(Routes.chat(ch.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ch.pinned) Icon(Icons.Filled.PushPin, null, tint = GravityColors.Cyan, modifier = Modifier.padding(end = 6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ch.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(relativeTime(ch.updatedAt) + (ch.model?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OverflowMenu(
                        listOf(
                            MenuAction("Rename", Icons.Filled.Edit) { renaming = ch },
                            MenuAction(if (ch.pinned) "Unpin" else "Pin", Icons.Filled.PushPin) { scope.launch { c.db.chats().upsert(ch.copy(pinned = !ch.pinned)) } },
                            MenuAction(if (ch.archived) "Restore" else "Archive", if (ch.archived) Icons.Filled.Unarchive else Icons.Filled.Archive) { scope.launch { c.db.chats().upsert(ch.copy(archived = !ch.archived)) } },
                            MenuAction("Duplicate", Icons.Filled.ContentCopy) {
                                scope.launch {
                                    val id = UUID.randomUUID().toString()
                                    c.db.chats().upsert(ch.copy(id = id, title = ch.title + " (copy)", createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
                                    c.db.chats().messages(ch.id).forEach { c.db.chats().insertMessage(it.copy(id = 0, chatId = id)) }
                                }
                            },
                            MenuAction("Delete", Icons.Filled.Delete, destructive = true) { deleting = ch },
                        )
                    )
                }
            }
        }
        if (messageHits.isNotEmpty()) {
            item { Text("Messages", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(messageHits, key = { "m" + it.id }) { m ->
                Column(Modifier.fillMaxWidth().clickable { go(Routes.chat(m.chatId)) }.padding(vertical = 6.dp)) {
                    Text(m.content.take(160), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(relativeTime(m.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    renaming?.let { ch ->
        TextInputDialog("Rename chat", "Title", ch.title, "Rename", onConfirm = { t -> scope.launch { c.db.chats().upsert(ch.copy(title = t)) } }, onDismiss = { renaming = null })
    }
    deleting?.let { ch ->
        ConfirmDialog("Delete chat?", "\"${ch.title}\" and all its messages will be deleted. Export it first if you need a copy (Export Center).", "Delete", destructive = true, onConfirm = {
            scope.launch {
                c.db.chats().deleteMessages(ch.id)
                c.db.chats().delete(ch.id)
            }
        }, onDismiss = { deleting = null })
    }
}
